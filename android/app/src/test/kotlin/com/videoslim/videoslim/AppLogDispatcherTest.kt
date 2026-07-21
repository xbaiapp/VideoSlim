package com.videoslim.videoslim

import java.io.File
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogDispatcherTest {
    @Test
    fun `progress coalesces independently by task and keeps latest within bounds`() {
        val executor = ManualExecutorService()
        val storage = RecordingLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 4,
                maxPendingBytes = 512,
                executor = executor,
                sessionId = "test",
            )

        repeat(250_000) { index -> dispatcher.progress("task-a", "progress=$index") }
        dispatcher.progress("task-b", "progress=other")

        val pending = dispatcher.pendingSnapshot()
        assertEquals(2, pending.commandCount)
        assertTrue(pending.byteCount <= 512)
        executor.runAll()

        assertEquals(2, storage.entries.size)
        assertTrue(storage.entries[0].endsWith("progress=249999"))
        assertTrue(storage.entries[1].endsWith("progress=other"))
    }

    @Test
    fun `priority records survive progress flooding and protected saturation rejects explicitly`() {
        val executor = ManualExecutorService()
        val storage = RecordingLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 3,
                maxPendingBytes = 1024,
                executor = executor,
                sessionId = "test",
            )

        val acceptedResults = mutableListOf<Result<Unit>>()
        dispatcher.progress("task-1", "progress=1")
        dispatcher.native("terminal") { acceptedResults += it }
        dispatcher.progress("task-2", "progress=2")
        repeat(100) { index -> dispatcher.progress("flood-$index", "progress=$index") }
        dispatcher.native("error") { acceptedResults += it }
        dispatcher.native("recovery") { acceptedResults += it }

        var rejectedCalls = 0
        var rejectedFailure = false
        dispatcher.native("launch") { outcome ->
            rejectedCalls += 1
            rejectedFailure = outcome.isFailure
        }
        assertEquals(1, rejectedCalls)
        assertTrue(rejectedFailure)

        executor.runAll()

        assertEquals(3, storage.entries.size)
        assertEquals(3, acceptedResults.size)
        assertTrue(acceptedResults.all { it.isSuccess })
        assertTrue(storage.entries[0].endsWith("terminal"))
        assertTrue(storage.entries[1].endsWith("error"))
        assertTrue(storage.entries[2].endsWith("recovery"))
    }

    @Test
    fun `coalescing moves latest progress after priority and never crosses a read barrier`() {
        val executor = ManualExecutorService()
        val storage = RecordingLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 8,
                maxPendingBytes = 2048,
                executor = executor,
                sessionId = "test",
            )

        var terminalResult: Result<Unit>? = null
        dispatcher.progress("task", "progress=old")
        dispatcher.native("terminal") { terminalResult = it }
        dispatcher.progress("task", "progress=latest")

        var readResult: Result<String>? = null
        dispatcher.readAll { readResult = it }
        dispatcher.progress("task", "progress=after-read")
        executor.runAll()

        assertEquals(3, storage.entries.size)
        assertTrue(terminalResult!!.isSuccess)
        assertTrue(storage.entries[0].endsWith("terminal"))
        assertTrue(storage.entries[1].endsWith("progress=latest"))
        assertTrue(storage.entries[2].endsWith("progress=after-read"))
        val observed = readResult!!.getOrThrow()
        assertTrue(observed.contains("terminal"))
        assertTrue(observed.contains("progress=latest"))
        assertFalse(observed.contains("progress=after-read"))
    }

    @Test
    fun `priority pressure fails an evicted normal completion exactly once`() {
        val executor = ManualExecutorService()
        val storage = RecordingLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 2,
                maxPendingBytes = 1024,
                executor = executor,
                sessionId = "test",
            )
        var firstCalls = 0
        var firstFailed = false
        var secondCalls = 0

        dispatcher.append("normal-1") { outcome ->
            firstCalls += 1
            firstFailed = outcome.isFailure
        }
        dispatcher.append("normal-2") { secondCalls += 1 }
        var priorityResult: Result<Unit>? = null
        dispatcher.native("priority") { priorityResult = it }

        assertEquals(1, firstCalls)
        assertTrue(firstFailed)
        executor.runAll()

        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
        assertTrue(priorityResult!!.isSuccess)
        assertEquals(listOf("normal-2"), storage.entries.filter { it.startsWith("normal") })
        assertTrue(storage.entries.last().endsWith("priority"))
    }

    @Test
    fun `shutdown drains accepted writes and rejects later submissions exactly once`() {
        val executor = ManualExecutorService()
        val storage = RecordingLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 4,
                maxPendingBytes = 1024,
                executor = executor,
                sessionId = "test",
            )
        var acceptedCalls = 0
        var shutdownCalls = 0
        var rejectedCalls = 0

        dispatcher.append("accepted") { outcome ->
            assertTrue(outcome.isSuccess)
            acceptedCalls += 1
        }
        dispatcher.shutdown { outcome ->
            assertTrue(outcome.isSuccess)
            shutdownCalls += 1
        }
        dispatcher.append("rejected") { outcome ->
            assertTrue(outcome.isFailure)
            rejectedCalls += 1
        }

        assertEquals(1, rejectedCalls)
        assertEquals(0, acceptedCalls)
        executor.runAll()

        assertEquals(listOf("accepted"), storage.entries)
        assertEquals(1, acceptedCalls)
        assertEquals(1, shutdownCalls)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun `all backend operations use one writer without overlap`() {
        val storage = RecordingLogStorage(delayMillis = 1)
        val writer = Executors.newSingleThreadExecutor()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 1000,
                maxPendingBytes = 256 * 1024,
                executor = writer,
                sessionId = "test",
            )
        val producers = Executors.newFixedThreadPool(8)
        val producerDone = CountDownLatch(400)
        repeat(400) { index ->
            producers.execute {
                dispatcher.append("entry-$index")
                producerDone.countDown()
            }
        }
        assertTrue(producerDone.await(10, TimeUnit.SECONDS))
        producers.shutdown()
        assertTrue(producers.awaitTermination(10, TimeUnit.SECONDS))

        val operationsDone = CountDownLatch(3)
        dispatcher.readAll { operationsDone.countDown() }
        dispatcher.createShareSnapshot { operationsDone.countDown() }
        dispatcher.flush { operationsDone.countDown() }
        assertTrue(operationsDone.await(10, TimeUnit.SECONDS))

        val shutdownDone = CountDownLatch(1)
        dispatcher.shutdown { shutdownDone.countDown() }
        assertTrue(shutdownDone.await(10, TimeUnit.SECONDS))
        assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, storage.maxActiveCalls.get())
        assertEquals(1, storage.writerThreads.size)
        assertEquals(400, storage.entries.size)
    }

    @Test
    fun `share barrier includes prior writes and excludes later writes`() {
        val executor = ManualExecutorService()
        val storage = RecordingLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 8,
                maxPendingBytes = 2048,
                executor = executor,
                sessionId = "test",
            )
        var snapshotResult: Result<File>? = null

        dispatcher.append("before-share")
        dispatcher.createShareSnapshot { snapshotResult = it }
        dispatcher.append("after-share")
        executor.runAll()

        assertEquals("before-share\n", snapshotResult!!.getOrThrow().readText())
        assertEquals(listOf("before-share", "after-share"), storage.entries)
    }

    @Test
    fun `backend failures complete once and do not terminate the writer`() {
        val executor = ManualExecutorService()
        val storage = FailingOnceLogStorage()
        val dispatcher =
            AppLogDispatcher(
                storage = storage,
                maxPendingCommands = 12,
                maxPendingBytes = 4096,
                executor = executor,
                sessionId = "test",
            )
        val appendResults = mutableListOf<Result<Unit>>()
        val readResults = mutableListOf<Result<String>>()
        val snapshotResults = mutableListOf<Result<File>>()

        dispatcher.append("fails") { appendResults += it }
        dispatcher.append("survives") { appendResults += it }
        dispatcher.readAll { readResults += it }
        dispatcher.readAll { readResults += it }
        dispatcher.createShareSnapshot { snapshotResults += it }
        dispatcher.createShareSnapshot { snapshotResults += it }
        executor.runAll()

        assertEquals(2, appendResults.size)
        assertTrue(appendResults[0].isFailure)
        assertTrue(appendResults[1].isSuccess)
        assertEquals(2, readResults.size)
        assertTrue(readResults[0].isFailure)
        assertEquals("survives\n", readResults[1].getOrThrow())
        assertEquals(2, snapshotResults.size)
        assertTrue(snapshotResults[0].isFailure)
        assertEquals("survives\n", snapshotResults[1].getOrThrow().readText())
    }
}

private class ManualExecutorService : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var shutdown = false
    private var running = false

    override fun execute(command: Runnable) {
        if (shutdown) throw RejectedExecutionException("executor is shut down")
        tasks.addLast(command)
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        val remaining = tasks.toMutableList()
        tasks.clear()
        return remaining
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty() && !running

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

    fun runAll() {
        while (tasks.isNotEmpty()) {
            val task = tasks.removeFirst()
            running = true
            try {
                task.run()
            } finally {
                running = false
            }
        }
    }
}

private class RecordingLogStorage(
    private val delayMillis: Long = 0,
) : AppLogStorage {
    val entries = Collections.synchronizedList(mutableListOf<String>())
    val writerThreads = Collections.synchronizedSet(mutableSetOf<String>())
    val maxActiveCalls = AtomicInteger()
    private val activeCalls = AtomicInteger()
    private val snapshotFile = File.createTempFile("videoslim-log-test", ".txt").apply { deleteOnExit() }

    override fun append(entry: String) = recordCall {
        entries += entry
    }

    override fun readAll(): String = recordCall {
        synchronized(entries) {
            if (entries.isEmpty()) "" else entries.joinToString(separator = "\n", postfix = "\n")
        }
    }

    override fun createShareSnapshot(): File = recordCall {
        snapshotFile.writeText(readAllWithoutRecording())
        snapshotFile
    }

    private fun readAllWithoutRecording(): String = synchronized(entries) {
        if (entries.isEmpty()) "" else entries.joinToString(separator = "\n", postfix = "\n")
    }

    private fun <T> recordCall(block: () -> T): T {
        writerThreads += Thread.currentThread().name
        val active = activeCalls.incrementAndGet()
        maxActiveCalls.updateAndGet { previous -> maxOf(previous, active) }
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            return block()
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

private class FailingOnceLogStorage : AppLogStorage {
    private val entries = mutableListOf<String>()
    private val snapshotFile = File.createTempFile("videoslim-log-failure-test", ".txt").apply { deleteOnExit() }
    private var failAppend = true
    private var failRead = true
    private var failSnapshot = true

    override fun append(entry: String) {
        if (failAppend) {
            failAppend = false
            throw IllegalStateException("append failed")
        }
        entries += entry
    }

    override fun readAll(): String {
        if (failRead) {
            failRead = false
            throw IllegalStateException("read failed")
        }
        return renderedEntries()
    }

    override fun createShareSnapshot(): File {
        if (failSnapshot) {
            failSnapshot = false
            throw IllegalStateException("snapshot failed")
        }
        snapshotFile.writeText(renderedEntries())
        return snapshotFile
    }

    private fun renderedEntries(): String =
        if (entries.isEmpty()) "" else entries.joinToString(separator = "\n", postfix = "\n")
}
