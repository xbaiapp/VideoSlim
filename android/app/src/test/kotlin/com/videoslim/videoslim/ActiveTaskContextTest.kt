package com.videoslim.videoslim

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTaskContextTest {
    @Test
    fun `context exclusively owns task kind generation and assigned engine route`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.AUDIO_EXTRACTION,
                launchGeneration = 7L,
            )

        assertTrue(context.owns("service-task", 7L))
        assertFalse(context.owns("other-task", 7L))
        assertFalse(context.owns("service-task", 6L))
        assertEquals(ActiveTaskLifecycle.AWAITING_ENGINE, context.lifecycle)

        val route = context.assignEngineTaskId(7L, "audio-engine-task")

        assertEquals(
            EngineTaskRoute(TaskKind.AUDIO_EXTRACTION, "audio-engine-task"),
            route,
        )
        assertEquals(route, context.assignEngineTaskId(7L, "audio-engine-task"))
        assertNull(context.assignEngineTaskId(7L, "different-engine-task"))
        assertTrue(context.acceptsEngineEvent(7L, "audio-engine-task"))
        assertFalse(context.acceptsEngineEvent(7L, "different-engine-task"))
        assertFalse(context.acceptsEngineEvent(6L, "audio-engine-task"))
        assertEquals("audio-engine-task", context.engineTaskId)
        assertEquals(ActiveTaskLifecycle.ENGINE_ASSIGNED, context.lifecycle)
        assertEquals(
            ActiveTaskCancellationDecision.CancelEngine(route!!),
            context.requestCancellation(
                taskId = "service-task",
                generation = 7L,
                source = ActiveTaskCancellationSource.USER,
            ),
        )
    }

    @Test
    fun `wrong task and stale generation cannot route or finish active ownership`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 9L,
            )
        val visibleTerminalCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        assertNull(context.assignEngineTaskId(8L, "stale-engine"))
        assertEquals(
            ActiveTaskCancellationDecision.Ignored,
            context.requestCancellation(
                taskId = "other-task",
                generation = 9L,
                source = ActiveTaskCancellationSource.USER,
            ),
        )
        assertEquals(
            ActiveTaskFinishDecision.StaleGeneration,
            context.finishOnce(
                generation = 8L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.ENGINE_TERMINAL,
                onWinner = { visibleTerminalCalls.incrementAndGet() },
                releaseResources = { releaseCalls.incrementAndGet() },
            ),
        )
        assertEquals(0, visibleTerminalCalls.get())
        assertEquals(0, releaseCalls.get())
        assertEquals(ActiveTaskLifecycle.AWAITING_ENGINE, context.lifecycle)

        val decision =
            context.finishOnce(
                generation = 9L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.ON_DESTROY,
                onWinner = { visibleTerminalCalls.incrementAndGet() },
                releaseResources = { releaseCalls.incrementAndGet() },
            )
        assertTrue(decision is ActiveTaskFinishDecision.Won)
        assertEquals(1, visibleTerminalCalls.get())
        assertEquals(1, releaseCalls.get())
    }

    @Test
    fun `terminal race has one visible winner and one resource release`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 11L,
            )
        val sources = ActiveTaskFinishSource.entries
        val ready = CountDownLatch(sources.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(sources.size)
        val decisions = Collections.synchronizedList(mutableListOf<ActiveTaskFinishDecision>())
        val visibleTerminalCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        sources.forEachIndexed { index, source ->
            executor.execute {
                ready.countDown()
                start.await()
                decisions +=
                    context.finishOnce(
                        generation = 11L,
                        outcome =
                            when (index % 3) {
                                0 -> ActiveTaskTerminalOutcome.SUCCEEDED
                                1 -> ActiveTaskTerminalOutcome.FAILED
                                else -> ActiveTaskTerminalOutcome.CANCELLED
                            },
                        source = source,
                        onWinner = { visibleTerminalCalls.incrementAndGet() },
                        releaseResources = { releaseCalls.incrementAndGet() },
                    )
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(1, decisions.count { it is ActiveTaskFinishDecision.Won })
        assertEquals(sources.size - 1, decisions.count { it is ActiveTaskFinishDecision.Lost })
        assertEquals(1, visibleTerminalCalls.get())
        assertEquals(1, releaseCalls.get())
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
        assertTrue(context.terminalOwnership != null)
    }

    @Test
    fun `cancellation before engine ID latches and prevents later launch`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 13L,
            )

        assertEquals(
            ActiveTaskCancellationDecision.FinishBeforeEngine,
            context.requestCancellation(
                taskId = "service-task",
                generation = 13L,
                source = ActiveTaskCancellationSource.USER,
            ),
        )
        assertTrue(context.isCancellationRequested)
        assertFalse(context.canLaunch(13L))
        assertNull(context.assignEngineTaskId(13L, "too-late-engine"))

        val decision =
            context.finishOnce(
                generation = 13L,
                outcome = ActiveTaskTerminalOutcome.CANCELLED,
                source = ActiveTaskFinishSource.CANCEL_BEFORE_ENGINE,
            )
        assertTrue(decision is ActiveTaskFinishDecision.Won)
        assertNull(context.engineTaskId)
        assertFalse(context.acceptsEngineEvent(13L, "too-late-engine"))
    }

    @Test
    fun `missing registry mirror cannot skip terminal resource cleanup`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 15L,
            )
        var registrySnapshot: TaskRuntimeSnapshot? = null
        val visibleTerminalCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        val decision =
            context.finishOnce(
                generation = 15L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.START_EXCEPTION,
                onWinner = {
                    registrySnapshot?.let { visibleTerminalCalls.incrementAndGet() }
                },
                releaseResources = { releaseCalls.incrementAndGet() },
            )

        assertTrue(decision is ActiveTaskFinishDecision.Won)
        assertEquals(0, visibleTerminalCalls.get())
        assertEquals(1, releaseCalls.get())
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
    }

    @Test
    fun `resource release is idempotent even when terminal publication throws`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.AUDIO_EXTRACTION,
                launchGeneration = 17L,
            )
        context.assignEngineTaskId(17L, "engine-task")
        val releaseCalls = AtomicInteger()

        try {
            context.finishOnce(
                generation = 17L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.ENGINE_TERMINAL,
                onWinner = { throw IllegalStateException("registry disappeared") },
                releaseResources = { releaseCalls.incrementAndGet() },
            )
            throw AssertionError("Expected terminal publication failure")
        } catch (expected: IllegalStateException) {
            assertEquals("registry disappeared", expected.message)
        }

        val loser =
            context.finishOnce(
                generation = 17L,
                outcome = ActiveTaskTerminalOutcome.CANCELLED,
                source = ActiveTaskFinishSource.CANCEL_TIMEOUT,
                releaseResources = { releaseCalls.incrementAndGet() },
            )
        assertTrue(loser is ActiveTaskFinishDecision.Lost)
        assertEquals(1, releaseCalls.get())
        assertNull(context.engineTaskId)
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
    }
}
