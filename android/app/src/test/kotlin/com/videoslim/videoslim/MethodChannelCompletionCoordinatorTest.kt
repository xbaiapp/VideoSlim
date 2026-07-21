package com.videoslim.videoslim

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodChannelCompletionCoordinatorTest {
    @Test
    fun `success and failure race completes exactly once`() {
        val scheduled = ManualMethodActionDispatcher()
        val coordinator = MethodChannelCompletionCoordinator(scheduled::dispatch)
        val outcomes = mutableListOf<String>()
        val completion = coordinator.register { outcomes += "disposed" }
        assertNotNull(completion)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val success = Thread {
            start.await()
            completion!!.complete { outcomes += "success" }
            done.countDown()
        }
        val failure = Thread {
            start.await()
            completion!!.complete { outcomes += "failure" }
            done.countDown()
        }

        success.start()
        failure.start()
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        scheduled.runAll()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() == "success" || outcomes.single() == "failure")
    }

    @Test
    fun `disposal wins a completion claimed before main delivery`() {
        val scheduled = ManualMethodActionDispatcher()
        val coordinator = MethodChannelCompletionCoordinator(scheduled::dispatch)
        val outcomes = mutableListOf<String>()
        val completion = coordinator.register { outcomes += "disposed" }!!

        assertTrue(completion.complete { outcomes += "success" })
        coordinator.dispose()
        assertFalse(completion.complete { outcomes += "failure" })
        scheduled.runAll()

        assertEquals(listOf("disposed"), outcomes)
    }

    @Test
    fun `delivered completion remains final when disposal follows`() {
        val scheduled = ManualMethodActionDispatcher()
        val coordinator = MethodChannelCompletionCoordinator(scheduled::dispatch)
        val outcomes = mutableListOf<String>()
        val completion = coordinator.register { outcomes += "disposed" }!!

        completion.complete { outcomes += "success" }
        scheduled.runAll()
        coordinator.dispose()
        scheduled.runAll()

        assertEquals(listOf("success"), outcomes)
    }

    @Test
    fun `synchronous executor rejection fails once and ignores late callback`() {
        val scheduled = ManualMethodActionDispatcher()
        val coordinator = MethodChannelCompletionCoordinator(scheduled::dispatch)
        val outcomes = mutableListOf<String>()
        val completion = coordinator.register { outcomes += "disposed" }!!

        completion.submit(
            submission = { throw RejectedExecutionException("rejected") },
            onSynchronousFailure = { outcomes += "failure:${it.message}" },
        )
        assertFalse(completion.complete { outcomes += "late-success" })
        scheduled.runAll()

        assertEquals(listOf("failure:rejected"), outcomes)
    }

    @Test
    fun `registration after disposal reports disposal once on dispatch target`() {
        val scheduled = ManualMethodActionDispatcher()
        val coordinator = MethodChannelCompletionCoordinator(scheduled::dispatch)
        val outcomes = mutableListOf<String>()

        coordinator.dispose()
        val completion = coordinator.register { outcomes += "disposed:${Thread.currentThread().name}" }
        assertTrue(outcomes.isEmpty())
        scheduled.runAllOn("fake-main")

        assertEquals(null, completion)
        assertEquals(listOf("disposed:fake-main"), outcomes)
    }

    @Test
    fun `successful delivery runs exactly once on dispatch target`() {
        val scheduled = ManualMethodActionDispatcher()
        val coordinator = MethodChannelCompletionCoordinator(scheduled::dispatch)
        val outcomes = mutableListOf<String>()
        val completion = coordinator.register { outcomes += "disposed" }!!

        assertTrue(completion.complete { outcomes += Thread.currentThread().name })
        assertFalse(completion.complete { outcomes += "duplicate" })
        scheduled.runAllOn("channel-main")

        assertEquals(listOf("channel-main"), outcomes)
    }
}

private class ManualMethodActionDispatcher {
    private val actions = ArrayDeque<() -> Unit>()

    fun dispatch(action: () -> Unit) {
        synchronized(actions) { actions.addLast(action) }
    }

    fun runAll() {
        while (true) {
            val action = synchronized(actions) { actions.removeFirstOrNull() } ?: return
            action()
        }
    }

    fun runAllOn(name: String) {
        val done = CountDownLatch(1)
        Thread({
            runAll()
            done.countDown()
        }, name).start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }
}
