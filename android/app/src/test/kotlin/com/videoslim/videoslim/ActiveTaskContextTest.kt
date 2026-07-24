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
    fun `engine events are rejected until exact route is assigned`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 8L,
            )

        assertFalse(context.acceptsEngineEvent(8L, "not-yet-assigned"))
        assertFalse(context.acceptsEngineEvent(7L, "not-yet-assigned"))

        assertEquals(
            EngineTaskRoute(TaskKind.VIDEO_COMPRESSION, "assigned-engine"),
            context.assignEngineTaskId(8L, "assigned-engine"),
        )
        assertTrue(context.acceptsEngineEvent(8L, "assigned-engine"))
        assertFalse(context.acceptsEngineEvent(8L, "different-engine"))
    }

    @Test
    fun `video engine route can transfer exactly once to an automatic retry`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 81L,
            )
        val hardwareRoute = checkNotNull(context.assignEngineTaskId(81L, "hardware-engine"))

        val softwareRoute =
            context.replaceEngineTaskIdForAutomaticRetry(
                generation = 81L,
                previousEngineTaskId = hardwareRoute.engineTaskId,
                retryEngineTaskId = "software-engine",
            )

        assertEquals(
            EngineTaskRoute(TaskKind.VIDEO_COMPRESSION, "software-engine"),
            softwareRoute,
        )
        assertFalse(context.acceptsEngineEvent(81L, "hardware-engine"))
        assertTrue(context.acceptsEngineEvent(81L, "software-engine"))
        assertNull(
            context.replaceEngineTaskIdForAutomaticRetry(
                generation = 81L,
                previousEngineTaskId = "software-engine",
                retryEngineTaskId = "forbidden-second-retry",
            ),
        )
    }

    @Test
    fun `automatic retry route transfer rejects stale wrong audio and cancelled ownership`() {
        val stale =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 82L,
            ).also { it.assignEngineTaskId(82L, "hardware-engine") }
        assertNull(
            stale.replaceEngineTaskIdForAutomaticRetry(
                generation = 81L,
                previousEngineTaskId = "hardware-engine",
                retryEngineTaskId = "software-engine",
            ),
        )
        assertNull(
            stale.replaceEngineTaskIdForAutomaticRetry(
                generation = 82L,
                previousEngineTaskId = "wrong-engine",
                retryEngineTaskId = "software-engine",
            ),
        )

        val audio =
            ActiveTaskContext(
                serviceTaskId = "audio-task",
                taskKind = TaskKind.AUDIO_EXTRACTION,
                launchGeneration = 83L,
            ).also { it.assignEngineTaskId(83L, "audio-engine") }
        assertNull(
            audio.replaceEngineTaskIdForAutomaticRetry(
                generation = 83L,
                previousEngineTaskId = "audio-engine",
                retryEngineTaskId = "software-engine",
            ),
        )

        val cancelled =
            ActiveTaskContext(
                serviceTaskId = "cancelled-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 84L,
            ).also { it.assignEngineTaskId(84L, "hardware-engine") }
        cancelled.requestCancellation(
            taskId = "cancelled-task",
            generation = 84L,
            source = ActiveTaskCancellationSource.USER,
        )
        assertNull(
            cancelled.replaceEngineTaskIdForAutomaticRetry(
                generation = 84L,
                previousEngineTaskId = "hardware-engine",
                retryEngineTaskId = "software-engine",
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
                publishTerminal = { visibleTerminalCalls.incrementAndGet() },
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
                publishTerminal = { visibleTerminalCalls.incrementAndGet() },
                releaseResources = { releaseCalls.incrementAndGet() },
            )
        assertTrue(decision is ActiveTaskFinishDecision.Won)
        assertEquals(1, visibleTerminalCalls.get())
        assertEquals(1, releaseCalls.get())
    }

    @Test
    fun `terminal winner remains finishing with route until idempotent completion`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 10L,
            )
        val route = checkNotNull(context.assignEngineTaskId(10L, "engine-task"))
        val visibleTerminalCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        var completeWinner: (() -> Unit)? = null

        val winner =
            context.finishOnce(
                generation = 10L,
                outcome = ActiveTaskTerminalOutcome.SUCCEEDED,
                source = ActiveTaskFinishSource.ENGINE_TERMINAL,
                onWinner = { completion -> completeWinner = completion },
                publishTerminal = { visibleTerminalCalls.incrementAndGet() },
                releaseResources = { releaseCalls.incrementAndGet() },
            )

        assertTrue(winner is ActiveTaskFinishDecision.Won)
        assertEquals(ActiveTaskLifecycle.FINISHING, context.lifecycle)
        assertEquals(route, context.engineRoute)
        assertEquals(0, visibleTerminalCalls.get())
        assertEquals(0, releaseCalls.get())

        val loser =
            context.finishOnce(
                generation = 10L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.SERVICE_TIMEOUT,
                onWinner = { throw AssertionError("loser callback must not run") },
                publishTerminal = { visibleTerminalCalls.incrementAndGet() },
                releaseResources = { releaseCalls.incrementAndGet() },
            )
        assertTrue(loser is ActiveTaskFinishDecision.Lost)
        assertEquals((winner as ActiveTaskFinishDecision.Won).ownership, (loser as ActiveTaskFinishDecision.Lost).ownership)
        assertEquals(ActiveTaskLifecycle.FINISHING, context.lifecycle)
        assertEquals(route, context.engineRoute)

        checkNotNull(completeWinner).invoke()
        checkNotNull(completeWinner).invoke()

        assertEquals(1, visibleTerminalCalls.get())
        assertEquals(1, releaseCalls.get())
        assertNull(context.engineRoute)
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
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
                        publishTerminal = { visibleTerminalCalls.incrementAndGet() },
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
                publishTerminal = {
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
                publishTerminal = { throw IllegalStateException("registry disappeared") },
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
                source = ActiveTaskFinishSource.USER_CANCEL_TIMEOUT,
                releaseResources = { releaseCalls.incrementAndGet() },
            )
        assertTrue(loser is ActiveTaskFinishDecision.Lost)
        assertEquals(1, releaseCalls.get())
        assertNull(context.engineTaskId)
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
    }

    @Test
    fun `winner scheduling failure completes immediately and cannot strand resources`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 19L,
            )
        context.assignEngineTaskId(19L, "engine-task")
        val visibleTerminalCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        try {
            context.finishOnce(
                generation = 19L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.ENGINE_TERMINAL,
                onWinner = { throw IllegalStateException("cleanup scheduling rejected") },
                publishTerminal = { visibleTerminalCalls.incrementAndGet() },
                releaseResources = { releaseCalls.incrementAndGet() },
            )
            throw AssertionError("Expected cleanup scheduling failure")
        } catch (expected: IllegalStateException) {
            assertEquals("cleanup scheduling rejected", expected.message)
        }

        assertEquals(1, visibleTerminalCalls.get())
        assertEquals(1, releaseCalls.get())
        assertNull(context.engineRoute)
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
    }
}
