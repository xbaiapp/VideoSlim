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
        harness.assertNoTerminalCleanup()
    }

    @Test
    fun `explicit completion post rejection claims no-engine fallback and releases service`() {
        val expected = IllegalStateException("main queue rejected completion")
        val harness = LaunchHarness()
        harness.coordinator.start()
        harness.rejectNextMainPost(expected)

        harness.completeReconciliation()

        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.MAIN_DISPATCH to expected), harness.failures)
        assertTrue(harness.reports.any { it.second === expected })
        harness.assertTerminalCleanupExactlyOnce(
            ActiveTaskFinishSource.START_EXCEPTION,
            expectEngineDisposal = false,
        )
    }

    @Test
    fun `thrown completion post is contained by no-engine fallback`() {
        val expected = AssertionError("main queue post threw")
        val harness = LaunchHarness()
        harness.coordinator.start()
        harness.rejectNextMainPost(expected, throwInstead = true)

        harness.completeReconciliation()

        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.MAIN_DISPATCH to expected), harness.failures)
        assertTrue(harness.reports.any { it.second === expected })
        harness.assertTerminalCleanupExactlyOnce(
            ActiveTaskFinishSource.START_EXCEPTION,
            expectEngineDisposal = false,
        )
    }

    @Test
    fun `completion post rejection wins queued watchdog exactly once`() {
        val expected = IllegalStateException("completion dispatch rejected")
        val harness = LaunchHarness()
        harness.coordinator.start()
        harness.fireRecoveryTimeout()
        harness.rejectNextMainPost(expected)

        harness.completeReconciliation()
        harness.runMainActions()

        assertEquals(0, harness.timeoutCount)
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.MAIN_DISPATCH to expected), harness.failures)
        harness.assertTerminalCleanupExactlyOnce(
            ActiveTaskFinishSource.START_EXCEPTION,
            expectEngineDisposal = false,
        )
    }

    @Test
    fun `watchdog cancellation throw cannot block claimed launch or termination`() {
        val launchCancelFailure = IllegalStateException("launch watchdog cancel failed")
        val launchHarness = LaunchHarness(recoveryWatchdogCancelFailure = launchCancelFailure)
        launchHarness.coordinator.start()
        launchHarness.completeReconciliation()
        launchHarness.runMainActions()

        assertEquals(1, launchHarness.launchCount)
        assertTrue(launchHarness.failures.isEmpty())
        assertTrue(launchHarness.reports.any { it.second === launchCancelFailure })

        val terminalCancelFailure = IllegalStateException("terminal watchdog cancel failed")
        val terminalHarness = LaunchHarness(recoveryWatchdogCancelFailure = terminalCancelFailure)
        val reconciliationFailure = IllegalStateException("reconciliation failed")
        terminalHarness.coordinator.start()
        terminalHarness.completeReconciliation(reconciliationFailure)
        terminalHarness.runMainActions()

        assertEquals(0, terminalHarness.launchCount)
        assertEquals(
            listOf(ReconciliationLaunchFailure.RECONCILIATION to reconciliationFailure),
            terminalHarness.failures,
        )
        assertTrue(terminalHarness.reports.any { it.second === terminalCancelFailure })
        terminalHarness.assertTerminalCleanupExactlyOnce(ActiveTaskFinishSource.START_EXCEPTION)
    }

    @Test
    fun `reconciliation failure finishes exactly once without launching an engine`() {
        val harness = LaunchHarness()
        val expected = IllegalStateException("recovery unavailable")
        harness.coordinator.start()

        harness.completeReconciliation(expected)
        harness.fireRecoveryTimeout()
        harness.runMainActions()

        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.RECONCILIATION to expected), harness.failures)
        assertEquals(1, harness.watchdogCancelCount)
        harness.assertTerminalCleanupExactlyOnce(ActiveTaskFinishSource.START_EXCEPTION)
    }

    @Test
    fun `invalid current reservations use one stable ownership failure and release service`() {
        val cases =
            listOf(
                InvalidReservationCase(
                    name = "missing reservation",
                    invalidate = { it.reservation = null },
                    resolve = { it.completeReconciliation() },
                ),
                InvalidReservationCase(
                    name = "mismatched task reservation",
                    invalidate = { harness ->
                        harness.reservation = harness.reservation?.copy(taskId = "other-task")
                    },
                    resolve = { harness ->
                        harness.completeReconciliation(IllegalStateException("recovery failed first"))
                    },
                ),
                InvalidReservationCase(
                    name = "mismatched kind reservation",
                    invalidate = { harness ->
                        harness.reservation =
                            harness.reservation?.copy(taskKind = TaskKind.AUDIO_EXTRACTION)
                    },
                    resolve = { it.fireRecoveryTimeout() },
                ),
                InvalidReservationCase(
                    name = "terminal reservation",
                    invalidate = { harness ->
                        harness.reservation =
                            harness.reservation?.copy(
                                state = TaskRuntimeSnapshot.STATE_FAILED,
                                phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                                errorCode = EngineErrorCode.UNKNOWN.wireName,
                                errorMessage = "already terminal",
                            )
                    },
                    resolve = { it.completeReconciliation() },
                ),
            )

        cases.forEach { case ->
            val harness = LaunchHarness()
            harness.coordinator.start()
            case.invalidate(harness)

            case.resolve(harness)
            harness.runMainActions()

            assertEquals("${case.name} must not launch", 0, harness.launchCount)
            assertEquals("${case.name} must not enter timeout", 0, harness.timeoutCount)
            assertEquals("${case.name} must fail once", 1, harness.failures.size)
            assertEquals(
                "${case.name} must use ownership failure",
                ReconciliationLaunchFailure.OWNERSHIP,
                harness.failures.single().first,
            )
            assertEquals(
                RECONCILIATION_LAUNCH_OWNERSHIP_FAILURE_MESSAGE,
                harness.failures.single().second.message,
            )
            harness.assertTerminalCleanupExactlyOnce(ActiveTaskFinishSource.START_EXCEPTION)
        }
    }

    @Test
    fun `cancel timeout destroy and replaced terminal owners are not finished twice`() {
        val cases =
            listOf(
                StaleOwnerCase(
                    name = "cancelled",
                    ownTerminal = { harness ->
                        assertEquals(
                            ServiceTerminationResult.TERMINAL,
                            harness.terminationPolicy.handleCancel(harness.context.serviceTaskId),
                        )
                    },
                    expectedOutcome = ActiveTaskTerminalOutcome.CANCELLED,
                    expectedSource = ActiveTaskFinishSource.CANCEL_BEFORE_ENGINE,
                ),
                StaleOwnerCase(
                    name = "timed out",
                    ownTerminal = { harness -> harness.terminationPolicy.onRecoveryWaitTimeout() },
                    expectedSource = ActiveTaskFinishSource.SERVICE_TIMEOUT,
                ),
                StaleOwnerCase(
                    name = "destroyed",
                    ownTerminal = { harness ->
                        harness.destroyed = true
                        harness.terminationPolicy.onDestroy()
                    },
                    expectedSource = ActiveTaskFinishSource.ON_DESTROY,
                    expectStop = false,
                ),
                StaleOwnerCase(
                    name = "replaced after terminal",
                    ownTerminal = { harness ->
                        harness.finishExistingTerminal()
                        harness.activeContext = awaitingContext("replacement", GENERATION + 1L)
                        harness.installedGeneration = GENERATION + 1L
                    },
                    expectedSource = ActiveTaskFinishSource.START_EXCEPTION,
                ),
            )

        cases.forEach { case ->
            val harness = LaunchHarness()
            harness.coordinator.start()
            case.ownTerminal(harness)
            harness.assertTerminalCleanupExactlyOnce(
                expectedSource = case.expectedSource,
                expectStop = case.expectStop,
                expectedOutcome = case.expectedOutcome,
            )
            val terminalAttempts = harness.terminalAttemptSnapshot()

            harness.completeReconciliation()
            harness.runMainActions()

            assertEquals("${case.name} must not launch", 0, harness.launchCount)
            assertTrue("${case.name} must not add a reconciliation failure", harness.failures.isEmpty())
            assertEquals("${case.name} must not duplicate cleanup", terminalAttempts, harness.terminalAttemptSnapshot())
        }
    }

    @Test
    fun `finishing current owner is ignored until its existing terminal completion releases once`() {
        val harness = LaunchHarness(deferTerminalWinner = true)
        harness.coordinator.start()
        harness.finishExistingTerminal()
        assertEquals(ActiveTaskLifecycle.FINISHING, harness.context.lifecycle)

        harness.completeReconciliation()
        harness.runMainActions()

        assertEquals(0, harness.launchCount)
        assertTrue(harness.failures.isEmpty())
        harness.assertNoTerminalCleanup()

        harness.runTerminalWinner()
        harness.assertTerminalCleanupExactlyOnce(ActiveTaskFinishSource.START_EXCEPTION)
    }

    @Test
    fun `bounded recovery watchdog timeout wins completion and enters timeout path`() {
        val harness = LaunchHarness()
        harness.coordinator.start()

        harness.fireRecoveryTimeout()
        harness.completeReconciliation()
        harness.runMainActions()

        assertEquals(1, harness.timeoutCount)
        assertEquals(0, harness.launchCount)
        assertTrue(harness.failures.isEmpty())
        harness.assertTerminalCleanupExactlyOnce(ActiveTaskFinishSource.SERVICE_TIMEOUT)
    }

    @Test
    fun `watchdog scheduling rejection fails closed without reconciliation registration`() {
        val expected = IllegalStateException("scheduler rejected")
        val harness = LaunchHarness(watchdogRejection = expected)

        assertTrue(harness.coordinator.start())

        assertEquals(0, harness.registrationCount)
        assertEquals(0, harness.launchCount)
        assertEquals(listOf(ReconciliationLaunchFailure.WATCHDOG to expected), harness.failures)
        harness.assertTerminalCleanupExactlyOnce(
            expectedSource = ActiveTaskFinishSource.START_EXCEPTION,
            expectRecoveryWatchdogCancellation = false,
        )
    }

    private data class InvalidReservationCase(
        val name: String,
        val invalidate: (LaunchHarness) -> Unit,
        val resolve: (LaunchHarness) -> Unit,
    )

    private data class StaleOwnerCase(
        val name: String,
        val ownTerminal: (LaunchHarness) -> Unit,
        val expectedOutcome: ActiveTaskTerminalOutcome = ActiveTaskTerminalOutcome.FAILED,
        val expectedSource: ActiveTaskFinishSource,
        val expectStop: Boolean = true,
    )

    private class LaunchHarness(
        private val watchdogRejection: Throwable? = null,
        private val deferTerminalWinner: Boolean = false,
        private val recoveryWatchdogCancelFailure: Throwable? = null,
    ) {
        val context = awaitingContext(SERVICE_TASK_ID, GENERATION)
        var destroyed = false
        var activeContext: ActiveTaskContext? = context
        var installedGeneration = GENERATION
        var reservation: TaskRuntimeSnapshot? = runningSnapshot(SERVICE_TASK_ID)
        val events = mutableListOf<String>()
        val failures = mutableListOf<Pair<ReconciliationLaunchFailure, Throwable>>()
        val reports = mutableListOf<Pair<String, Throwable>>()
        val postedMainActions = ArrayDeque<() -> Unit>()
        var registrationCount = 0
        var launchCount = 0
        var timeoutCount = 0
        var watchdogCancelCount = 0
        private var recoveryWatchdogArmed = false
        private var completion: ((Throwable?) -> Unit)? = null
        private var recoveryTimeout: (() -> Unit)? = null
        private var terminalWinner: (() -> Unit)? = null
        private var nextMainPostFailure: Throwable? = null
        private var nextMainPostThrows = false
        private var registryTerminalCount = 0
        private var userWatchdogCancelCount = 0
        private var observerReleaseCount = 0
        private var wakeReleaseCount = 0
        private var foregroundReleaseCount = 0
        private var noEngineForegroundReleaseCount = 0
        private var notificationCount = 0
        private var noEngineNotificationCount = 0
        private var transcodeDisposeCount = 0
        private var audioDisposeCount = 0
        private var clearActiveCount = 0
        private var stopCount = 0

        private fun cancelRecoveryWatchdog() {
            if (recoveryWatchdogArmed) {
                recoveryWatchdogArmed = false
                watchdogCancelCount += 1
                recoveryWatchdogCancelFailure?.let { throw it }
            }
        }

        private val cancellationWatchdog =
            UserCancellationWatchdog(
                scheduler = CancellationWatchdogScheduler { _, _ -> CancellationWatchdogRegistration {} },
                timeoutMillis = 5_000L,
            )

        val terminationPolicy =
            ServiceTerminationPolicy(
                context = context,
                cancellationWatchdog = cancellationWatchdog,
                actions =
                    ServiceTerminationPolicyActions(
                        markPublicationDiscarding = {},
                        cancelEngine = {},
                        publishRegistryTerminal = { registryTerminalCount += 1 },
                        scheduleTerminalWinner = { action ->
                            if (deferTerminalWinner) terminalWinner = action else action()
                        },
                        cancelRecoveryWaitWatchdog = ::cancelRecoveryWatchdog,
                        cancelUserWatchdog = {
                            userWatchdogCancelCount += 1
                            cancellationWatchdog.cancel()
                        },
                        removeRegistryObserver = { observerReleaseCount += 1 },
                        releaseWakeLock = { wakeReleaseCount += 1 },
                        removeForeground = { foregroundReleaseCount += 1 },
                        removeForegroundForNoEngineFallback = {
                            foregroundReleaseCount += 1
                            noEngineForegroundReleaseCount += 1
                        },
                        publishTerminalNotification = { notificationCount += 1 },
                        publishTerminalNotificationForNoEngineFallback = {
                            notificationCount += 1
                            noEngineNotificationCount += 1
                        },
                        disposeTranscodeEngine = { transcodeDisposeCount += 1 },
                        disposeAudioExtractionEngine = { audioDisposeCount += 1 },
                        ownsServiceSurface = {
                            activeContext === context && installedGeneration == GENERATION
                        },
                        clearActiveLaunch = {
                            clearActiveCount += 1
                            if (activeContext === context) activeContext = null
                        },
                        stopService = { stopCount += 1 },
                    ),
            )

        val coordinator =
            ServiceReconciliationLaunchCoordinator(
                actions =
                    ServiceReconciliationLaunchActions(
                        startForeground = { events += "foreground" },
                        acquireWakeLock = { events += "wake" },
                        armRecoveryWaitWatchdog = { onTimeout ->
                            events += "watchdog-arm"
                            watchdogRejection?.let {
                                return@ServiceReconciliationLaunchActions RecoveryWaitWatchdogArmResult.Rejected(it)
                            }
                            recoveryWatchdogArmed = true
                            recoveryTimeout = onTimeout
                            RecoveryWaitWatchdogArmResult.Armed
                        },
                        cancelRecoveryWaitWatchdog = ::cancelRecoveryWatchdog,
                        registerReconciliationCompletion = { callback ->
                            events += "register"
                            registrationCount += 1
                            completion = callback
                        },
                        postToServiceMain = { action ->
                            val failure = nextMainPostFailure
                            nextMainPostFailure = null
                            if (failure == null) {
                                postedMainActions += action
                                ServiceMainDispatchResult.Accepted
                            } else if (nextMainPostThrows) {
                                nextMainPostThrows = false
                                throw failure
                            } else {
                                ServiceMainDispatchResult.Rejected(failure)
                            }
                        },
                        revalidateLaunch = {
                            reconciliationLaunchDisposition(
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
                        finishFailure = { reason, error ->
                            failures += reason to error
                            terminationPolicy.finish(
                                ServiceTerminalDirective(
                                    outcome = ActiveTaskTerminalOutcome.FAILED,
                                    source = ActiveTaskFinishSource.START_EXCEPTION,
                                    errorCode = EngineErrorCode.UNKNOWN.wireName,
                                    errorMessage = EngineErrorCode.UNKNOWN.defaultMessage,
                                ),
                            )
                        },
                        finishNoEngineFailure = { reason, error ->
                            failures += reason to error
                            terminationPolicy.finishNoEngineFailure(
                                ServiceTerminalDirective(
                                    outcome = ActiveTaskTerminalOutcome.FAILED,
                                    source = ActiveTaskFinishSource.START_EXCEPTION,
                                    errorCode = EngineErrorCode.UNKNOWN.wireName,
                                    errorMessage = EngineErrorCode.UNKNOWN.defaultMessage,
                                ),
                            )
                        },
                        onRecoveryWaitTimeout = {
                            timeoutCount += 1
                            terminationPolicy.onRecoveryWaitTimeout()
                        },
                        onFailure = { operation, error -> reports += operation to error },
                    ),
            )

        fun completeReconciliation(error: Throwable? = null) {
            checkNotNull(completion).invoke(error)
        }

        fun fireRecoveryTimeout() {
            checkNotNull(recoveryTimeout).invoke()
        }

        fun rejectNextMainPost(
            error: Throwable,
            throwInstead: Boolean = false,
        ) {
            check(nextMainPostFailure == null)
            nextMainPostFailure = error
            nextMainPostThrows = throwInstead
        }

        fun finishExistingTerminal() {
            terminationPolicy.finish(
                ServiceTerminalDirective(
                    outcome = ActiveTaskTerminalOutcome.FAILED,
                    source = ActiveTaskFinishSource.START_EXCEPTION,
                    errorCode = EngineErrorCode.UNKNOWN.wireName,
                    errorMessage = "existing terminal",
                ),
            )
        }

        fun runMainActions() {
            while (postedMainActions.isNotEmpty()) postedMainActions.removeFirst().invoke()
        }

        fun runTerminalWinner() {
            checkNotNull(terminalWinner).invoke()
        }

        fun terminalAttemptSnapshot(): List<Int> =
            listOf(
                registryTerminalCount,
                watchdogCancelCount,
                userWatchdogCancelCount,
                observerReleaseCount,
                wakeReleaseCount,
                foregroundReleaseCount,
                notificationCount,
                transcodeDisposeCount,
                audioDisposeCount,
                clearActiveCount,
                stopCount,
            )

        fun assertNoTerminalCleanup() {
            assertEquals(listOf(0, watchdogCancelCount, 0, 0, 0, 0, 0, 0, 0, 0, 0), terminalAttemptSnapshot())
        }

        fun assertTerminalCleanupExactlyOnce(
            expectedSource: ActiveTaskFinishSource,
            expectStop: Boolean = true,
            expectedOutcome: ActiveTaskTerminalOutcome = ActiveTaskTerminalOutcome.FAILED,
            expectRecoveryWatchdogCancellation: Boolean = true,
            expectEngineDisposal: Boolean = true,
        ) {
            assertEquals(
                ActiveTaskTerminalOwnership(expectedOutcome, expectedSource),
                context.terminalOwnership,
            )
            assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
            assertEquals(
                listOf(
                    1,
                    if (expectRecoveryWatchdogCancellation) 1 else 0,
                    1,
                    1,
                    1,
                    1,
                    1,
                    if (expectEngineDisposal) 1 else 0,
                    if (expectEngineDisposal) 1 else 0,
                    1,
                    if (expectStop) 1 else 0,
                ),
                terminalAttemptSnapshot(),
            )
            assertEquals(if (expectEngineDisposal) 0 else 1, noEngineForegroundReleaseCount)
            assertEquals(if (expectEngineDisposal) 0 else 1, noEngineNotificationCount)
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
