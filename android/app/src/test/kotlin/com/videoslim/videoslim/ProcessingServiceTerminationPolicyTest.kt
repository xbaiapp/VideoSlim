package com.videoslim.videoslim

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceTerminationPolicyTest {
    @Test
    fun `watchdog scheduler throw returns rejected result and rolls back armed state`() {
        val scheduler = RecordingWatchdogScheduler(rejectNext = true)
        val watchdog = UserCancellationWatchdog(scheduler, timeoutMillis = 5_000L)

        val result = watchdog.arm { error("rejected timeout must not run") }

        assertTrue(result is UserCancellationWatchdogArmResult.Rejected)
        assertEquals("scheduler rejected", (result as UserCancellationWatchdogArmResult.Rejected).error.message)
        assertEquals(
            UserCancellationWatchdogArmResult.Armed,
            watchdog.arm { error("unused second timeout") },
        )
        watchdog.cancel()
    }

    @Test
    fun `watchdog scheduler rejection fails closed after engine cancel and rolls back armed state`() {
        val scheduler = RecordingWatchdogScheduler(rejectNext = true)
        val harness = PolicyHarness(scheduler = scheduler)

        val result = harness.policy.handleCancel(harness.context.serviceTaskId)

        assertEquals(ServiceTerminationResult.TERMINAL, result)
        assertEquals(
            listOf("watchdog-arm", "discard", "cancel-engine", "registry", "watchdog-cancel") +
                RESOURCE_RELEASE_ORDER,
            harness.events,
        )
        assertEquals(
            ActiveTaskTerminalOwnership(
                ActiveTaskTerminalOutcome.CANCELLED,
                ActiveTaskFinishSource.USER_CANCEL_TIMEOUT,
            ),
            harness.context.terminalOwnership,
        )
        assertEquals(1, harness.notificationAttempts.get())
        assertEquals(1, harness.stopAttempts.get())
        assertTrue(harness.failures.any { it.first == "user cancellation watchdog arm" })

        // A rejected scheduler registration must not leave the watchdog armed.
        assertEquals(
            UserCancellationWatchdogArmResult.Armed,
            harness.watchdog.arm { error("unused second timeout") },
        )
        harness.watchdog.cancel()
    }

    @Test
    fun `accepted user cancellation arms before cancel and watchdog closes cancel failure`() {
        val scheduler = RecordingWatchdogScheduler()
        val harness = PolicyHarness(scheduler = scheduler, cancelFailure = IllegalStateException("cancel failed"))

        assertEquals(
            ServiceTerminationResult.AWAITING_ENGINE_TERMINAL,
            harness.policy.handleCancel(harness.context.serviceTaskId),
        )
        assertEquals(listOf("watchdog-arm", "discard", "cancel-engine"), harness.events)
        assertEquals(null, harness.context.terminalOwnership)
        assertTrue(harness.failures.any { it.first == "engine cancellation" })

        scheduler.fire()

        assertEquals(
            ActiveTaskTerminalOwnership(
                ActiveTaskTerminalOutcome.CANCELLED,
                ActiveTaskFinishSource.USER_CANCEL_TIMEOUT,
            ),
            harness.context.terminalOwnership,
        )
        assertEquals(1, harness.notificationAttempts.get())
        assertEquals(1, harness.stopAttempts.get())
        val completedEvents = harness.events.toList()

        assertEquals(ServiceTerminationResult.TERMINAL, harness.policy.handleCancel(harness.context.serviceTaskId))
        harness.policy.onDestroy()
        scheduler.fire()
        assertEquals(completedEvents, harness.events)
        harness.assertEveryResourceAttemptedOnce()
    }

    @Test
    fun `platform timeout cancels then finishes immediately and contains every cleanup failure`() {
        val harness =
            PolicyHarness(
                cancelFailure = IllegalStateException("cancel failed"),
                registryFailure = IllegalStateException("registry failed"),
                failingResources = RESOURCE_RELEASE_ORDER.toSet(),
            )

        assertEquals(ServiceTerminationResult.TERMINAL, harness.policy.onTimeout())

        assertEquals(
            listOf("discard", "cancel-engine", "registry", "watchdog-cancel") + RESOURCE_RELEASE_ORDER,
            harness.events,
        )
        assertEquals(
            ActiveTaskTerminalOwnership(
                ActiveTaskTerminalOutcome.FAILED,
                ActiveTaskFinishSource.SERVICE_TIMEOUT,
            ),
            harness.context.terminalOwnership,
        )
        harness.assertEveryResourceAttemptedOnce()
        assertEquals(1, harness.notificationAttempts.get())
        assertEquals(1, harness.stopAttempts.get())
        assertTrue(harness.failures.any { it.first == "registry terminal publication" })
        assertTrue(harness.failures.any { it.first == "service stop" })

        assertEquals(ServiceTerminationResult.TERMINAL, harness.policy.onTimeout())
        harness.policy.onDestroy()
        harness.assertEveryResourceAttemptedOnce()
        assertEquals(1, harness.stopAttempts.get())
    }

    @Test
    fun `service destruction cancels and finishes despite cancel failure without requesting stopSelfResult`() {
        val harness = PolicyHarness(cancelFailure = IllegalStateException("cancel failed"))

        assertEquals(ServiceTerminationResult.TERMINAL, harness.policy.onDestroy())

        assertEquals(
            listOf("discard", "cancel-engine", "registry", "watchdog-cancel") +
                RESOURCE_RELEASE_ORDER.filterNot { it == "stop" },
            harness.events,
        )
        assertEquals(
            ActiveTaskTerminalOwnership(
                ActiveTaskTerminalOutcome.FAILED,
                ActiveTaskFinishSource.ON_DESTROY,
            ),
            harness.context.terminalOwnership,
        )
        assertEquals(0, harness.stopAttempts.get())
        assertEquals(1, harness.notificationAttempts.get())

        assertEquals(ServiceTerminationResult.TERMINAL, harness.policy.onDestroy())
        assertEquals(0, harness.stopAttempts.get())
        harness.assertEveryResourceAttemptedOnce(expectStop = false)
    }

    @Test
    fun `terminal notification policy always supplies winner fallback and attempts exactly once`() {
        val mirrors =
            listOf(
                null,
                terminalSnapshot(taskId = "different", state = TaskRuntimeSnapshot.STATE_FAILED),
                terminalSnapshot(taskId = SERVICE_TASK_ID, state = TaskRuntimeSnapshot.STATE_RUNNING),
                terminalSnapshot(taskId = SERVICE_TASK_ID, state = TaskRuntimeSnapshot.STATE_FAILED),
            )

        mirrors.forEachIndexed { index, mirror ->
            val attempts = mutableListOf<ProcessingTerminalNotificationPayload>()
            val context = activeContext()
            val terminalNotifications =
                ServiceTerminalNotificationPolicy(
                    context = context,
                    registrySnapshot = {
                        if (index == 0) throw IllegalStateException("snapshot unavailable")
                        mirror
                    },
                    notifyTerminal = attempts::add,
                )
            val harness =
                PolicyHarness(
                    context = context,
                    registryFailure = IllegalStateException("registry or recovery publication failed"),
                    terminalNotification = terminalNotifications::attempt,
                )

            assertEquals(ServiceTerminationResult.TERMINAL, harness.policy.onTimeout())
            harness.policy.onDestroy()

            assertEquals(1, attempts.size)
            assertEquals(SERVICE_TASK_ID, attempts.single().taskId)
            assertEquals(TaskKind.VIDEO_COMPRESSION, attempts.single().taskKind)
            assertEquals(ActiveTaskTerminalOutcome.FAILED, attempts.single().outcome)
            assertEquals(ActiveTaskFinishSource.SERVICE_TIMEOUT, attempts.single().source)
            assertEquals(EngineErrorCode.UNKNOWN.wireName, attempts.single().errorCode)
            assertEquals("系统已结束超时的媒体处理任务", attempts.single().errorMessage)
            if (index == mirrors.lastIndex) {
                assertEquals(mirror, attempts.single().terminalSnapshot)
            } else {
                assertEquals(null, attempts.single().terminalSnapshot)
                assertEquals(TaskRuntimeSnapshot.STATE_FAILED, attempts.single().asSnapshot().state)
            }
            assertFalse(attempts.single().text.ongoing)
        }
    }

    private class PolicyHarness(
        val context: ActiveTaskContext = activeContext(),
        scheduler: RecordingWatchdogScheduler = RecordingWatchdogScheduler(),
        private val cancelFailure: Throwable? = null,
        private val registryFailure: Throwable? = null,
        private val failingResources: Set<String> = emptySet(),
        terminalNotification: ((ServiceTerminalDirective) -> Unit)? = null,
    ) {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, Throwable>>()
        val notificationAttempts = AtomicInteger()
        val stopAttempts = AtomicInteger()
        val watchdog = UserCancellationWatchdog(scheduler, timeoutMillis = 5_000L)
        private val resourceAttempts = RESOURCE_RELEASE_ORDER.associateWith { AtomicInteger() }
        val policy: ServiceTerminationPolicy

        init {
            scheduler.events = events
            policy =
                ServiceTerminationPolicy(
                    context = context,
                    cancellationWatchdog = watchdog,
                    actions =
                        ServiceTerminationPolicyActions(
                            markPublicationDiscarding = {
                                events += "discard"
                            },
                            cancelEngine = {
                                events += "cancel-engine"
                                cancelFailure?.let { throw it }
                            },
                            publishRegistryTerminal = {
                                events += "registry"
                                registryFailure?.let { throw it }
                            },
                            cancelUserWatchdog = {
                                events += "watchdog-cancel"
                                watchdog.cancel()
                            },
                            removeRegistryObserver = resource("observer"),
                            releaseWakeLock = resource("wake-lock"),
                            removeForeground = resource("foreground"),
                            publishTerminalNotification = { terminal ->
                                notificationAttempts.incrementAndGet()
                                resource("notification").invoke()
                                terminalNotification?.invoke(terminal)
                            },
                            disposeTranscodeEngine = resource("video-engine"),
                            disposeAudioExtractionEngine = resource("audio-engine"),
                            ownsServiceSurface = { true },
                            clearActiveLaunch = resource("clear-active"),
                            stopService = {
                                stopAttempts.incrementAndGet()
                                resource("stop").invoke()
                            },
                            onFailure = { operation, error -> failures += operation to error },
                        ),
                )
        }

        private fun resource(name: String): () -> Unit = {
            events += name
            resourceAttempts.getValue(name).incrementAndGet()
            if (name in failingResources) throw IllegalStateException("$name failed")
        }

        fun assertEveryResourceAttemptedOnce(expectStop: Boolean = true) {
            RESOURCE_RELEASE_ORDER.forEach { name ->
                assertEquals(
                    "attempt count for $name",
                    if (name == "stop" && !expectStop) 0 else 1,
                    resourceAttempts.getValue(name).get(),
                )
            }
        }
    }

    private class RecordingWatchdogScheduler(
        private var rejectNext: Boolean = false,
    ) : CancellationWatchdogScheduler {
        var events: MutableList<String> = mutableListOf()
        private var scheduled: (() -> Unit)? = null
        private var cancelled = false

        override fun schedule(
            timeoutMillis: Long,
            action: () -> Unit,
        ): CancellationWatchdogRegistration {
            events += "watchdog-arm"
            if (rejectNext) {
                rejectNext = false
                throw IllegalStateException("scheduler rejected")
            }
            scheduled = action
            cancelled = false
            return CancellationWatchdogRegistration { cancelled = true }
        }

        fun fire() {
            if (!cancelled) scheduled?.invoke()
        }
    }

    private companion object {
        const val SERVICE_TASK_ID = "service-task"
        const val ENGINE_TASK_ID = "123e4567-e89b-12d3-a456-426614174005"
        val RESOURCE_RELEASE_ORDER =
            listOf(
                "observer",
                "wake-lock",
                "foreground",
                "notification",
                "video-engine",
                "audio-engine",
                "clear-active",
                "stop",
            )

        fun activeContext(): ActiveTaskContext =
            ActiveTaskContext(
                serviceTaskId = SERVICE_TASK_ID,
                taskKind = TaskKind.VIDEO_COMPRESSION,
                launchGeneration = 5L,
            ).also { context ->
                checkNotNull(context.assignEngineTaskId(context.launchGeneration, ENGINE_TASK_ID))
            }

        fun terminalSnapshot(
            taskId: String,
            state: String,
        ): TaskRuntimeSnapshot =
            TaskRuntimeSnapshot(
                taskId = taskId,
                percent = 42.0,
                state = state,
                phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                sourceUri = "content://source/video",
                outputFileName = "movie.mp4",
                startedAtEpochMs = 1L,
                errorCode = if (state == TaskRuntimeSnapshot.STATE_FAILED) EngineErrorCode.UNKNOWN.wireName else null,
                errorMessage = if (state == TaskRuntimeSnapshot.STATE_FAILED) "failed" else null,
            )
    }
}
