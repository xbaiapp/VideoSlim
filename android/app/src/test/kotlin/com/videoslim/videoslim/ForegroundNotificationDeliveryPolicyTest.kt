package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationDeliveryPolicyTest {
    @Test
    fun `worker observer candidate queued before terminal cleanup cannot recreate ongoing notification`() {
        val context = activeContext(taskId = "service-task", generation = 7L)
        val running = runningSnapshot(taskId = context.serviceTaskId, percent = 42.0)
        var activeContext: ActiveTaskContext? = context
        var registrySnapshot: TaskRuntimeSnapshot? = running
        val events = mutableListOf<String>()
        val mainQueue = ArrayDeque<() -> Unit>()
        val candidate = candidate(context, running)

        // A registry observer running on a worker only queues the exact delivery candidate.
        mainQueue.addLast {
            if (
                ForegroundNotificationDeliveryPolicy.shouldDeliver(
                    candidate = candidate,
                    activeContext = activeContext,
                    registrySnapshot = registrySnapshot,
                    serviceDestroyed = false,
                )
            ) {
                events += "foreground-notified"
            }
        }

        // Terminal cleanup wins on main before the previously queued delivery is dispatched.
        context.finishOnce(
            generation = context.launchGeneration,
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.ENGINE_TERMINAL,
            publishTerminal = {
                registrySnapshot = failedSnapshot(running)
            },
            releaseResources = {
                events += "foreground-removed"
                events += "terminal-notified"
                activeContext = null
            },
        )
        mainQueue.removeFirst().invoke()

        assertEquals(
            listOf("foreground-removed", "terminal-notified"),
            events,
        )
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
    }

    @Test
    fun `current owner and exact current running snapshot are delivered`() {
        val context = activeContext(taskId = "service-task", generation = 11L)
        val running = runningSnapshot(taskId = context.serviceTaskId, percent = 54.0)
        val candidate = candidate(context, running)

        assertTrue(
            ForegroundNotificationDeliveryPolicy.shouldDeliver(
                candidate = candidate,
                activeContext = context,
                registrySnapshot = running,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `stale owner identity or generation is rejected`() {
        val oldContext = activeContext(taskId = "service-task", generation = 20L)
        val newContext = activeContext(taskId = "service-task", generation = 21L)
        val running = runningSnapshot(taskId = oldContext.serviceTaskId, percent = 20.0)
        val oldCandidate = candidate(oldContext, running)

        assertFalse(
            ForegroundNotificationDeliveryPolicy.shouldDeliver(
                candidate = oldCandidate,
                activeContext = newContext,
                registrySnapshot = running,
                serviceDestroyed = false,
            ),
        )
        assertFalse(
            ForegroundNotificationDeliveryPolicy.shouldDeliver(
                candidate = oldCandidate.copy(launchGeneration = oldContext.launchGeneration + 1L),
                activeContext = oldContext,
                registrySnapshot = running,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `superseded terminal or different-task registry snapshot is rejected`() {
        val context = activeContext(taskId = "service-task", generation = 30L)
        val candidateSnapshot = runningSnapshot(taskId = context.serviceTaskId, percent = 30.0)
        val candidate = candidate(context, candidateSnapshot)

        val staleSnapshots =
            listOf(
                candidateSnapshot.copy(percent = 31.0),
                failedSnapshot(candidateSnapshot),
                runningSnapshot(taskId = "different-task", percent = 30.0),
            )
        staleSnapshots.forEach { currentSnapshot ->
            assertFalse(
                ForegroundNotificationDeliveryPolicy.shouldDeliver(
                    candidate = candidate,
                    activeContext = context,
                    registrySnapshot = currentSnapshot,
                    serviceDestroyed = false,
                ),
            )
        }
    }

    @Test
    fun `terminal claim and destroyed service reject otherwise current candidate`() {
        val context = activeContext(taskId = "service-task", generation = 40L)
        val running = runningSnapshot(taskId = context.serviceTaskId, percent = 40.0)
        val candidate = candidate(context, running)
        var finishWinner: (() -> Unit)? = null

        context.finishOnce(
            generation = context.launchGeneration,
            outcome = ActiveTaskTerminalOutcome.CANCELLED,
            source = ActiveTaskFinishSource.ENGINE_TERMINAL,
            onWinner = { completion -> finishWinner = completion },
        )

        assertEquals(ActiveTaskLifecycle.FINISHING, context.lifecycle)
        assertFalse(
            ForegroundNotificationDeliveryPolicy.shouldDeliver(
                candidate = candidate,
                activeContext = context,
                registrySnapshot = running,
                serviceDestroyed = false,
            ),
        )
        checkNotNull(finishWinner).invoke()

        val freshContext = activeContext(taskId = "fresh-task", generation = 41L)
        val freshRunning = runningSnapshot(taskId = freshContext.serviceTaskId, percent = 41.0)
        assertFalse(
            ForegroundNotificationDeliveryPolicy.shouldDeliver(
                candidate = candidate(freshContext, freshRunning),
                activeContext = freshContext,
                registrySnapshot = freshRunning,
                serviceDestroyed = true,
            ),
        )
    }

    private fun candidate(
        context: ActiveTaskContext,
        snapshot: TaskRuntimeSnapshot,
    ): ForegroundNotificationDeliveryCandidate =
        ForegroundNotificationDeliveryCandidate(
            context = context,
            launchGeneration = context.launchGeneration,
            taskId = context.serviceTaskId,
            snapshot = snapshot,
        )

    private fun activeContext(
        taskId: String,
        generation: Long,
    ): ActiveTaskContext =
        ActiveTaskContext(
            serviceTaskId = taskId,
            taskKind = TaskKind.VIDEO_COMPRESSION,
            launchGeneration = generation,
        ).also { context ->
            checkNotNull(
                context.assignEngineTaskId(
                    generation = generation,
                    engineTaskId = "engine-$generation",
                ),
            )
        }

    private fun runningSnapshot(
        taskId: String,
        percent: Double,
    ): TaskRuntimeSnapshot =
        TaskRuntimeSnapshot(
            taskId = taskId,
            percent = percent,
            state = TaskRuntimeSnapshot.STATE_RUNNING,
            phase = TaskRuntimeSnapshot.PHASE_ENCODING,
            sourceUri = "content://source/$taskId",
            outputFileName = "$taskId.mp4",
            startedAtEpochMs = 1L,
        )

    private fun failedSnapshot(running: TaskRuntimeSnapshot): TaskRuntimeSnapshot =
        running.copy(
            state = TaskRuntimeSnapshot.STATE_FAILED,
            phase = TaskRuntimeSnapshot.PHASE_FINISHED,
            errorCode = EngineErrorCode.UNKNOWN.wireName,
            errorMessage = "failed",
        )
}
