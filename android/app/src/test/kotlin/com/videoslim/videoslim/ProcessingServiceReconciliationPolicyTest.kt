package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceReconciliationPolicyTest {
    @Test
    fun `foreground and wake acquisition precede one reconciliation registration`() {
        val harness = LaunchHarness()

        assertTrue(harness.coordinator.start())
        assertFalse(harness.coordinator.start())

        assertEquals(listOf("foreground", "wake", "watchdog-arm", "register"), harness.events)
        assertEquals(1, harness.registrationCount)
        assertEquals(0, harness.launchCount)
    }

    @Test
    fun `engine launch is posted and occurs only after successful reconciliation`() {
        val harness = LaunchHarness()
        harness.coordinator.start()

        harness.completeReconciliation()

        assertEquals(0, harness.launchCount)
        assertEquals(1, harness.postedMainActions.size)
        harness.runMainActions()
        assertEquals(1, harness.launchCount)
        assertEquals(1, harness.watchdogCancelCount)
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun `reconciliation failure finishes without launching an engine`() {
        val harness = LaunchHarness()
        val expected = IllegalStateException("recovery unavailable")
        harness.coordinator.start()

        harness.completeReconciliation(expected)
        harness.runMainActions()

        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.RECONCILIATION to expected), harness.failures)
        assertEquals(1, harness.watchdogCancelCount)
    }

    @Test
    fun `cancellation timeout destruction and stale ownership while waiting never launch`() {
        val scenarios =
            listOf<Pair<String, (LaunchHarness) -> Unit>>(
                "cancel" to { harness ->
                    harness.context.requestCancellation(
                        taskId = harness.context.serviceTaskId,
                        generation = harness.context.launchGeneration,
                        source = ActiveTaskCancellationSource.USER,
                    )
                },
                "timeout" to { harness ->
                    harness.context.requestCancellation(
                        taskId = harness.context.serviceTaskId,
                        generation = harness.context.launchGeneration,
                        source = ActiveTaskCancellationSource.TIMEOUT,
                    )
                },
                "destroy" to { it.destroyed = true },
                "stale context" to { it.activeContext = awaitingContext("replacement", GENERATION) },
                "stale generation" to { it.installedGeneration = GENERATION + 1L },
                "missing reservation" to { it.reservation = null },
                "terminal reservation" to {
                    it.reservation = it.reservation?.copy(state = TaskRuntimeSnapshot.STATE_FAILED)
                },
            )

        scenarios.forEach { (name, makeStale) ->
            val harness = LaunchHarness()
            harness.coordinator.start()
            makeStale(harness)

            harness.completeReconciliation()
            harness.runMainActions()

            assertEquals("$name must not launch", 0, harness.launchCount)
            assertTrue("$name must not replace its existing terminal path", harness.failures.isEmpty())
        }
    }

    @Test
    fun `bounded recovery watchdog timeout wins completion and enters timeout path`() {
        val harness = LaunchHarness()
        harness.coordinator.start()

        harness.fireRecoveryTimeout()
        harness.runMainActions()
        harness.completeReconciliation()
        harness.runMainActions()

        assertEquals(1, harness.timeoutCount)
        assertEquals(0, harness.launchCount)
        assertTrue(harness.failures.isEmpty())
    }

    @Test
    fun `watchdog scheduling rejection fails closed without reconciliation registration`() {
        val expected = IllegalStateException("scheduler rejected")
        val harness = LaunchHarness(watchdogRejection = expected)

        assertTrue(harness.coordinator.start())

        assertEquals(listOf("foreground", "wake", "watchdog-arm", "watchdog-cancel"), harness.events)
        assertEquals(0, harness.registrationCount)
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.WATCHDOG to expected), harness.failures)
    }

    private class LaunchHarness(
        private val watchdogRejection: Throwable? = null,
    ) {
        val context = awaitingContext(SERVICE_TASK_ID, GENERATION)
        var destroyed = false
        var activeContext: ActiveTaskContext? = context
        var installedGeneration = GENERATION
        var reservation: TaskRuntimeSnapshot? = runningSnapshot(SERVICE_TASK_ID)
        val events = mutableListOf<String>()
        val failures = mutableListOf<Pair<ReconciliationLaunchFailure, Throwable>>()
        val postedMainActions = ArrayDeque<() -> Unit>()
        var registrationCount = 0
        var launchCount = 0
        var timeoutCount = 0
        var watchdogCancelCount = 0
        private var completion: ((Throwable?) -> Unit)? = null
        private var recoveryTimeout: (() -> Unit)? = null

        val coordinator =
            ServiceReconciliationLaunchCoordinator(
                actions =
                    ServiceReconciliationLaunchActions(
                        startForeground = { events += "foreground" },
                        acquireWakeLock = { events += "wake" },
                        armRecoveryWaitWatchdog = { onTimeout ->
                            events += "watchdog-arm"
                            watchdogRejection?.let { return@ServiceReconciliationLaunchActions RecoveryWaitWatchdogArmResult.Rejected(it) }
                            recoveryTimeout = onTimeout
                            RecoveryWaitWatchdogArmResult.Armed
                        },
                        cancelRecoveryWaitWatchdog = {
                            events += "watchdog-cancel"
                            watchdogCancelCount += 1
                            recoveryTimeout = null
                        },
                        registerReconciliationCompletion = { callback ->
                            events += "register"
                            registrationCount += 1
                            completion = callback
                        },
                        postToServiceMain = { action -> postedMainActions += action },
                        isLaunchCurrent = {
                            canLaunchAfterReconciliation(
                                serviceDestroyed = destroyed,
                                expectedContext = context,
                                activeContext = activeContext,
                                expectedGeneration = GENERATION,
                                installedGeneration = installedGeneration,
                                reservation = reservation,
                            )
                        },
                        launchEngine = {
                            events += "launch"
                            launchCount += 1
                        },
                        finishFailure = { reason, error -> failures += reason to error },
                        onRecoveryWaitTimeout = { timeoutCount += 1 },
                    ),
            )

        fun completeReconciliation(error: Throwable? = null) {
            checkNotNull(completion).invoke(error)
        }

        fun fireRecoveryTimeout() {
            checkNotNull(recoveryTimeout).invoke()
        }

        fun runMainActions() {
            while (postedMainActions.isNotEmpty()) postedMainActions.removeFirst().invoke()
        }
    }

    private companion object {
        const val SERVICE_TASK_ID = "service-task"
        const val GENERATION = 7L

        fun awaitingContext(
            taskId: String,
            generation: Long,
        ): ActiveTaskContext =
            ActiveTaskContext(
                serviceTaskId = taskId,
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = generation,
            )

        fun runningSnapshot(taskId: String): TaskRuntimeSnapshot =
            TaskRuntimeSnapshot(
                taskId = taskId,
                percent = 0.0,
                state = TaskRuntimeSnapshot.STATE_RUNNING,
                phase = TaskRuntimeSnapshot.PHASE_PREPARING,
                sourceUri = "content://source/video",
                outputFileName = "movie.mp4",
                startedAtEpochMs = 1L,
            )
    }
}
