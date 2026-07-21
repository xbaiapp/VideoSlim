package com.videoslim.videoslim

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceOwnershipPolicyTest {
    @Test
    fun `user cancellation watchdog wins when engine cancellation never reaches terminal`() {
        val context = activeContext("service-task", "engine-task", generation = 21L)
        val scheduler = ManualWatchdogScheduler()
        val watchdog = UserCancellationWatchdog(scheduler, timeoutMillis = 5_000L)
        val operations = CleanupOperations(watchdog)
        val cleanup = operations.cleanup()

        assertTrue(
            watchdog.arm {
                context.finishOnce(
                    generation = 21L,
                    outcome = ActiveTaskTerminalOutcome.CANCELLED,
                    source = ActiveTaskFinishSource.USER_CANCEL_TIMEOUT,
                    releaseResources = {
                        cleanup.releaseForTerminalWinner(includeServiceSurface = true)
                    },
                )
            },
        )
        assertEquals(
            ActiveTaskCancellationDecision.CancelEngine(
                EngineTaskRoute(TaskKind.VIDEO_COMPRESSION, "engine-task"),
            ),
            context.requestCancellation(
                taskId = "service-task",
                generation = 21L,
                source = ActiveTaskCancellationSource.USER,
            ),
        )

        // Model cancel() throwing or returning without ever producing a terminal callback.
        runCatching { throw IllegalStateException("cancel failed") }
        scheduler.fire()

        assertEquals(
            ActiveTaskTerminalOwnership(
                ActiveTaskTerminalOutcome.CANCELLED,
                ActiveTaskFinishSource.USER_CANCEL_TIMEOUT,
            ),
            context.terminalOwnership,
        )
        operations.assertReleasedExactlyOnce()
        val lateEngineTerminal =
            context.finishOnce(
                generation = 21L,
                outcome = ActiveTaskTerminalOutcome.CANCELLED,
                source = ActiveTaskFinishSource.ENGINE_TERMINAL,
            )
        assertTrue(lateEngineTerminal is ActiveTaskFinishDecision.Lost)
    }

    @Test
    fun `service timeout and destruction keep assigned route pending engine unwind`() {
        val timeoutContext = activeContext("timeout-service", "timeout-engine", generation = 25L)
        val destroyContext = activeContext("destroy-service", "destroy-engine", generation = 26L)

        assertEquals(
            ActiveTaskCancellationDecision.CancelEngine(
                EngineTaskRoute(TaskKind.VIDEO_COMPRESSION, "timeout-engine"),
            ),
            timeoutContext.requestCancellation(
                taskId = "timeout-service",
                generation = 25L,
                source = ActiveTaskCancellationSource.TIMEOUT,
            ),
        )
        assertEquals(ActiveTaskLifecycle.ENGINE_ASSIGNED, timeoutContext.lifecycle)
        assertEquals("timeout-engine", timeoutContext.engineTaskId)
        assertEquals(
            ActiveTaskCancellationDecision.CancelEngine(
                EngineTaskRoute(TaskKind.VIDEO_COMPRESSION, "destroy-engine"),
            ),
            destroyContext.requestCancellation(
                taskId = "destroy-service",
                generation = 26L,
                source = ActiveTaskCancellationSource.SERVICE_DESTROY,
            ),
        )
        assertEquals(ActiveTaskLifecycle.ENGINE_ASSIGNED, destroyContext.lifecycle)
        assertEquals("destroy-engine", destroyContext.engineTaskId)
    }

    @Test
    fun `service timeout and destruction claim terminal ownership and release engines`() {
        val cases =
            listOf(
                Triple(
                    activeContext("timeout-service", "timeout-engine", generation = 28L),
                    ActiveTaskCancellationSource.TIMEOUT,
                    ActiveTaskFinishSource.SERVICE_TIMEOUT,
                ),
                Triple(
                    activeContext("destroy-service", "destroy-engine", generation = 29L),
                    ActiveTaskCancellationSource.SERVICE_DESTROY,
                    ActiveTaskFinishSource.ON_DESTROY,
                ),
            )

        cases.forEach { (context, cancellationSource, finishSource) ->
            val operations = CleanupOperations(UserCancellationWatchdog(ManualWatchdogScheduler(), 5_000L))
            val cleanup = operations.cleanup()
            assertTrue(
                context.requestCancellation(
                    taskId = context.serviceTaskId,
                    generation = context.launchGeneration,
                    source = cancellationSource,
                ) is ActiveTaskCancellationDecision.CancelEngine,
            )

            assertTrue(
                context.finishOnce(
                    generation = context.launchGeneration,
                    outcome = ActiveTaskTerminalOutcome.FAILED,
                    source = finishSource,
                    releaseResources = {
                        cleanup.releaseForTerminalWinner(includeServiceSurface = true)
                    },
                ) is ActiveTaskFinishDecision.Won,
            )

            assertEquals(
                ActiveTaskTerminalOwnership(ActiveTaskTerminalOutcome.FAILED, finishSource),
                context.terminalOwnership,
            )
            assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
            assertFalse(cleanup.releaseForServiceDestroy())
            operations.assertReleasedExactlyOnce()
        }
    }

    @Test
    fun `service destruction without an engine can complete immediately`() {
        val context =
            ActiveTaskContext(
                serviceTaskId = "service-task",
                taskKind = TaskKind.AUDIO_EXTRACTION,
                launchGeneration = 27L,
            )

        assertEquals(
            ActiveTaskCancellationDecision.FinishBeforeEngine,
            context.requestCancellation(
                taskId = "service-task",
                generation = 27L,
                source = ActiveTaskCancellationSource.SERVICE_DESTROY,
            ),
        )
        assertTrue(
            context.finishOnce(
                generation = 27L,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.ON_DESTROY,
            ) is ActiveTaskFinishDecision.Won,
        )
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
    }

    @Test
    fun `service timeout keeps late publication callback bound to timed out owner`() {
        val context = activeContext("old-service", OLD_ENGINE_TASK_ID, generation = 31L)
        val owner = ActiveTaskPublicationOwner(context)
        assertTrue(owner.bindEngineRoute(context.engineRoute!!))
        val journal = RecordingPublicationJournal(record(OLD_ENGINE_TASK_ID, "movie.mp4"))
        val reportedOwners = mutableListOf<Pair<PublicationLaunchIdentity, String>>()
        val observer =
            RecoveryPublicationObserver(
                journal = journal,
                owner = owner,
                onOutputFileName = { identity, name -> reportedOwners += identity to name },
            )
        val uri = "content://media/external/video/media/31"
        val target = PublicationTarget(uri, "movie.mp4")
        observer.onPublicationUriAllocated(uri)

        context.requestCancellation(
            taskId = "old-service",
            generation = 31L,
            source = ActiveTaskCancellationSource.TIMEOUT,
        )
        var completeWinner: (() -> Unit)? = null
        context.finishOnce(
            generation = 31L,
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.SERVICE_TIMEOUT,
            onWinner = { completion -> completeWinner = completion },
        )

        assertEquals(ActiveTaskLifecycle.FINISHING, context.lifecycle)
        assertEquals(OLD_ENGINE_TASK_ID, context.engineTaskId)
        // Publication I/O unwinds against the retained owner before winner completion releases it.
        observer.onPublicationTargetAllocated(target)
        observer.onPublicationCompleted(target)
        checkNotNull(completeWinner).invoke()

        assertEquals(RecoveryStage.DISCARDING, journal.record.stage)
        assertEquals(OLD_ENGINE_TASK_ID, journal.record.taskId)
        assertEquals(ActiveTaskLifecycle.RELEASED, context.lifecycle)
        assertEquals(
            listOf(PublicationLaunchIdentity("old-service", 31L, OLD_ENGINE_TASK_ID) to "movie.mp4"),
            reportedOwners,
        )
    }

    @Test
    fun `stale publication owner never attaches callback to a newer generation`() {
        val oldContext = activeContext("old-service", OLD_ENGINE_TASK_ID, generation = 41L)
        val newContext = activeContext("new-service", NEW_ENGINE_TASK_ID, generation = 42L)
        val oldOwner = ActiveTaskPublicationOwner(oldContext).also { it.bindEngineRoute(oldContext.engineRoute!!) }
        val newOwner = ActiveTaskPublicationOwner(newContext).also { it.bindEngineRoute(newContext.engineRoute!!) }
        val oldJournal = RecordingPublicationJournal(record(OLD_ENGINE_TASK_ID, "old.mp4"))
        val newJournal = RecordingPublicationJournal(record(NEW_ENGINE_TASK_ID, "new.mp4"))
        val callbacks = mutableListOf<PublicationLaunchIdentity>()
        val oldObserver =
            RecoveryPublicationObserver(
                journal = oldJournal,
                owner = oldOwner,
                onOutputFileName = { identity, _ -> callbacks += identity },
            )
        RecoveryPublicationObserver(journal = newJournal, owner = newOwner)

        val oldUri = "content://media/external/video/media/41"
        oldObserver.onPublicationUriAllocated(oldUri)
        oldObserver.onPublicationTargetAllocated(PublicationTarget(oldUri, "old.mp4"))

        assertEquals(RecoveryStage.PUBLISHING, oldJournal.record.stage)
        assertEquals(RecoveryStage.TRANSFORMING, newJournal.record.stage)
        assertEquals(
            listOf(PublicationLaunchIdentity("old-service", 41L, OLD_ENGINE_TASK_ID)),
            callbacks,
        )
    }

    @Test
    fun `terminal engine cleanup and onDestroy fallback are idempotent`() {
        val scheduler = ManualWatchdogScheduler()
        val watchdog = UserCancellationWatchdog(scheduler, timeoutMillis = 5_000L)
        val operations = CleanupOperations(watchdog)
        val cleanup = operations.cleanup()
        assertTrue(watchdog.arm { throw AssertionError("cancelled watchdog must not fire") })

        assertTrue(cleanup.releaseForTerminalWinner(includeServiceSurface = true))
        assertFalse(cleanup.releaseForServiceDestroy())
        scheduler.fire()

        operations.assertReleasedExactlyOnce()
        assertEquals(1, scheduler.cancelCalls.get())
    }

    private fun activeContext(
        serviceTaskId: String,
        engineTaskId: String,
        generation: Long,
    ): ActiveTaskContext =
        ActiveTaskContext(
            serviceTaskId = serviceTaskId,
            taskKind = TaskKind.VIDEO_COMPRESSION,
            launchGeneration = generation,
        ).also { context ->
            checkNotNull(context.assignEngineTaskId(generation, engineTaskId))
        }

    private fun record(taskId: String, outputName: String): TaskRecoveryRecord =
        TaskRecoveryRecord(
            taskId = taskId,
            stage = RecoveryStage.TRANSFORMING,
            tempFileName = "$taskId.mp4",
            expectedOutputDisplayName = outputName,
            actualOutputDisplayName = null,
            mediaStoreUri = null,
            legacyOutputPath = null,
            startedAtEpochMs = 1L,
        )

    private class ManualWatchdogScheduler : CancellationWatchdogScheduler {
        private var scheduled: Scheduled? = null
        val cancelCalls = AtomicInteger()

        override fun schedule(
            timeoutMillis: Long,
            action: () -> Unit,
        ): CancellationWatchdogRegistration {
            require(timeoutMillis > 0L)
            val entry = Scheduled(action)
            scheduled = entry
            return CancellationWatchdogRegistration {
                if (!entry.cancelled) {
                    entry.cancelled = true
                    cancelCalls.incrementAndGet()
                }
            }
        }

        fun fire() {
            scheduled?.takeIf { !it.cancelled }?.action?.invoke()
        }

        private class Scheduled(
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class CleanupOperations(
        private val watchdog: UserCancellationWatchdog,
    ) {
        private val observer = AtomicInteger()
        private val wakeLock = AtomicInteger()
        private val foreground = AtomicInteger()
        private val notification = AtomicInteger()
        private val videoEngine = AtomicInteger()
        private val audioEngine = AtomicInteger()

        fun cleanup(): ServiceTaskCleanup =
            ServiceTaskCleanup(
                actions =
                    ServiceTaskCleanupActions(
                        cancelUserWatchdog = watchdog::cancel,
                        removeRegistryObserver = { observer.incrementAndGet() },
                        releaseWakeLock = { wakeLock.incrementAndGet() },
                        removeForeground = { foreground.incrementAndGet() },
                        publishTerminalNotification = { notification.incrementAndGet() },
                        disposeTranscodeEngine = { videoEngine.incrementAndGet() },
                        disposeAudioExtractionEngine = { audioEngine.incrementAndGet() },
                    ),
            )

        fun assertReleasedExactlyOnce() {
            assertEquals(1, observer.get())
            assertEquals(1, wakeLock.get())
            assertEquals(1, foreground.get())
            assertEquals(1, notification.get())
            assertEquals(1, videoEngine.get())
            assertEquals(1, audioEngine.get())
        }
    }

    private class RecordingPublicationJournal(
        var record: TaskRecoveryRecord,
    ) : PublicationRecoveryJournal {
        override fun recordPublicationAllocation(
            taskId: String,
            publicationUri: String,
        ): TaskRecoveryRecord {
            require(record.taskId == taskId)
            record = withPublicationAllocation(record, publicationUri)
            return record
        }

        override fun recordPublicationTarget(
            taskId: String,
            actualOutputDisplayName: String,
            mediaStoreUri: String,
            canonicalLegacyOutputPath: String?,
            mediaKind: OutputMediaKind,
        ): TaskRecoveryRecord {
            require(record.taskId == taskId)
            record =
                withPublicationTarget(
                    current = record,
                    actualOutputDisplayName = actualOutputDisplayName,
                    mediaStoreUri = mediaStoreUri,
                    canonicalLegacyOutputPath = canonicalLegacyOutputPath,
                    mediaKind = mediaKind,
                )
            return record
        }

        override fun markPublished(taskId: String): TaskRecoveryRecord = transition(taskId, RecoveryStage.PUBLISHED)

        override fun markDiscarding(taskId: String): TaskRecoveryRecord = transition(taskId, RecoveryStage.DISCARDING)

        private fun transition(taskId: String, stage: RecoveryStage): TaskRecoveryRecord {
            require(record.taskId == taskId)
            require(isAllowedRecoveryTransition(record.stage, stage))
            record = record.copy(stage = stage)
            return record
        }
    }

    private companion object {
        const val OLD_ENGINE_TASK_ID = "123e4567-e89b-12d3-a456-426614174031"
        const val NEW_ENGINE_TASK_ID = "123e4567-e89b-12d3-a456-426614174042"
    }
}
