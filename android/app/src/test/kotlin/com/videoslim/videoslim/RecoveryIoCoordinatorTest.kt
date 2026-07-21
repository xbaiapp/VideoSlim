package com.videoslim.videoslim

import java.io.File
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryIoCoordinatorTest {
    @Test
    fun `API 26 policy excludes post-floor CompletableFuture views`() {
        productionSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension in setOf("java", "kt") }
            .forEach { source ->
                assertFalse(
                    "${source.path} must use only future APIs available on Android API 26",
                    source.readText().contains("minimalCompletionStage"),
                )
            }
    }

    @Test
    fun `completion access never starts reconciliation`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        val observed = AtomicInteger()

        gate.completion().whenComplete { _, _ -> observed.incrementAndGet() }

        assertEquals(0, executor.queuedCount)
        assertEquals(0, observed.get())
        assertFalse(gate.completion().toCompletableFuture().isDone)
        assertEquals(0, executor.shutdownCalls.get())
    }

    @Test
    fun `concurrent callers receive one stage and execute one action asynchronously`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        val actions = AtomicInteger()
        val callers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val stages = Collections.synchronizedList(mutableListOf<Any>())

        repeat(2) {
            callers.execute {
                ready.countDown()
                start.await()
                stages += gate.startOnce { actions.incrementAndGet() }
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        callers.shutdown()
        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(1, executor.queuedCount)
        assertEquals(0, actions.get())
        assertSame(stages[0], stages[1])
        assertSame(stages[0], gate.completion())

        executor.runNext()

        assertEquals(1, actions.get())
        assertTrue(gate.completion().toCompletableFuture().isDone)
        assertFalse(gate.completion().toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, executor.shutdownCalls.get())
    }

    @Test
    fun `early and late listeners share successful completion`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        val events = mutableListOf<String>()
        val stage = gate.startOnce { events += "action" }
        stage.whenComplete { _, failure ->
            assertEquals(null, failure)
            events += "early"
        }

        executor.runNext()
        gate.completion().whenComplete { _, failure ->
            assertEquals(null, failure)
            events += "late"
        }

        assertEquals(listOf("action", "early", "late"), events)
        assertSame(stage, gate.startOnce { error("must not execute") })
        assertEquals(1, executor.shutdownCalls.get())
    }

    @Test
    fun `early callers cannot complete or cancel shared reconciliation`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        val actions = AtomicInteger()
        val firstView = gate.startOnce { actions.incrementAndGet() }
        val secondView = gate.completion()
        val forcedFailure = firstView.toCompletableFuture()
        val cancelled = secondView.toCompletableFuture()
        val untouchedEarlyCopy = firstView.toCompletableFuture()

        assertFalse(firstView is CompletableFuture<*>)
        assertFalse(secondView is CompletableFuture<*>)
        assertNotSame(forcedFailure, untouchedEarlyCopy)
        assertTrue(forcedFailure.completeExceptionally(IllegalStateException("caller-forced failure")))
        assertTrue(cancelled.cancel(false))
        assertFalse(untouchedEarlyCopy.isDone)
        assertFalse(gate.completion().toCompletableFuture().isDone)

        executor.runNext()

        assertEquals(1, actions.get())
        assertTrue(forcedFailure.isCompletedExceptionally)
        assertTrue(cancelled.isCancelled)
        assertTrue(untouchedEarlyCopy.isDone)
        assertFalse(untouchedEarlyCopy.isCompletedExceptionally)
        assertTrue(gate.completion().toCompletableFuture().isDone)
        assertFalse(gate.completion().toCompletableFuture().isCompletedExceptionally)
    }

    @Test
    fun `late caller mutation cannot rewrite shared reconciliation result`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        gate.startOnce {}
        executor.runNext()

        val callerCopy = gate.completion().toCompletableFuture()
        val otherLateCopy = gate.startOnce { error("must not execute") }.toCompletableFuture()
        assertNotSame(callerCopy, otherLateCopy)
        callerCopy.obtrudeException(IllegalStateException("caller rewrote its copy"))

        assertTrue(callerCopy.isCompletedExceptionally)
        assertFalse(callerCopy.cancel(false))
        assertTrue(otherLateCopy.isDone)
        assertFalse(otherLateCopy.isCompletedExceptionally)
        assertFalse(gate.completion().toCompletableFuture().isCompletedExceptionally)
    }

    @Test
    fun `caller mutation and cancellation cannot rewrite failed reconciliation`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        val expected = IllegalStateException("reconciliation failed")
        val earlyForcedSuccess = gate.startOnce { throw expected }.toCompletableFuture()
        val earlyCancelled = gate.completion().toCompletableFuture()
        val untouchedEarlyCopy = gate.completion().toCompletableFuture()

        assertTrue(earlyForcedSuccess.complete(Unit))
        assertTrue(earlyCancelled.cancel(false))
        executor.runNext()

        assertSame(expected, untouchedEarlyCopy.failureCause())
        val mutatedLateCopy = gate.completion().toCompletableFuture()
        val untouchedLateCopy = gate.startOnce { error("must not execute") }.toCompletableFuture()
        mutatedLateCopy.obtrudeValue(Unit)

        assertFalse(mutatedLateCopy.isCompletedExceptionally)
        assertFalse(mutatedLateCopy.cancel(false))
        assertSame(expected, untouchedLateCopy.failureCause())
        assertSame(expected, gate.completion().toCompletableFuture().failureCause())
    }

    @Test
    fun `action failure fans out to every listener and still shuts down`() {
        val executor = ManualExecutorService()
        val gate = ProcessReconciliationGate(executor)
        val expected = IllegalStateException("reconciliation failed")
        val failures = mutableListOf<Throwable?>()
        val stage = gate.startOnce { throw expected }
        stage.whenComplete { _, failure -> failures += failure }
        gate.completion().whenComplete { _, failure -> failures += failure }

        executor.runNext()
        gate.startOnce { error("must not execute") }.whenComplete { _, failure -> failures += failure }

        assertEquals(3, failures.size)
        failures.forEach { assertSame(expected, it.completionCause()) }
        assertTrue(stage.toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, executor.shutdownCalls.get())
    }

    @Test
    fun `submission rejection completes shared stage exceptionally and shuts down`() {
        val executor = ManualExecutorService(rejectExecution = true)
        val gate = ProcessReconciliationGate(executor)
        val failures = mutableListOf<Throwable?>()

        val first = gate.startOnce { error("rejected action must not run") }
        first.whenComplete { _, failure -> failures += failure }
        val second = gate.startOnce { error("second action must not run") }
        second.whenComplete { _, failure -> failures += failure }

        assertSame(first, second)
        assertTrue(first.toCompletableFuture().isCompletedExceptionally)
        assertEquals(2, failures.size)
        failures.forEach { assertTrue(it.completionCause() is RejectedExecutionException) }
        assertEquals(1, executor.shutdownCalls.get())
    }

    private fun Throwable?.completionCause(): Throwable? =
        if (this is CompletionException) cause else this

    private fun CompletableFuture<Unit>.failureCause(): Throwable? =
        try {
            join()
            null
        } catch (error: CompletionException) {
            error.cause
        }

    private fun productionSourceRoot(): File {
        val relativePath = "src/main"
        return listOf(File(relativePath), File("app", relativePath), File("android/app", relativePath))
            .firstOrNull(File::isDirectory)
            ?: error("Cannot locate $relativePath from ${File(".").absolutePath}")
    }

    private class ManualExecutorService(
        private val rejectExecution: Boolean = false,
    ) : AbstractExecutorService() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private var shutdown = false
        val shutdownCalls = AtomicInteger()

        val queuedCount: Int
            get() = queue.size

        override fun execute(command: Runnable) {
            if (rejectExecution) throw RejectedExecutionException("manual rejection")
            check(!shutdown) { "executor is shut down" }
            queue += command
        }

        fun runNext() {
            checkNotNull(queue.poll()) { "No queued reconciliation action" }.run()
        }

        override fun shutdown() {
            shutdownCalls.incrementAndGet()
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown()
            return mutableListOf<Runnable>().also { remaining ->
                while (true) remaining += queue.poll() ?: break
            }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && queue.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }
}
