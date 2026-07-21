package com.videoslim.videoslim

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

internal fun validatedStartTaskKind(
    snapshot: TaskRuntimeSnapshot,
    wireName: Any?,
): TaskKind {
    val taskKind = TaskKind.fromWireName(wireName)
        ?: throw IllegalArgumentException("processing start requires an explicit task kind")
    require(taskKind == snapshot.taskKind) { "processing start task kind does not match reservation" }
    return taskKind
}

internal class RecoveryPublicationObserver(
    private val journal: PublicationRecoveryJournal,
    private val taskId: () -> String?,
    private val cancellationRequested: () -> Boolean,
    private val onOutputFileName: (String) -> Unit = {},
    private val logger: (String) -> Unit = {},
) : PublicationObserver {
    override fun onPublicationUriAllocated(publicationUri: String) {
        val internalTaskId =
            taskId() ?: throw IllegalStateException("Publication allocated without an engine task")
        journal.recordPublicationAllocation(internalTaskId, publicationUri)
        logger("task=$internalTaskId publication allocated uri=$publicationUri")
    }

    override fun onPublicationTargetAllocated(target: PublicationTarget) {
        val internalTaskId =
            taskId() ?: throw IllegalStateException("Publication started without an engine task")
        journal.recordPublicationTarget(
            taskId = internalTaskId,
            actualOutputDisplayName = target.actualDisplayName,
            mediaStoreUri = target.mediaStoreUri,
            canonicalLegacyOutputPath = target.canonicalLegacyOutputPath,
            mediaKind = target.mediaKind,
        )
        if (cancellationRequested()) journal.markDiscarding(internalTaskId)
        logger(
            "task=$internalTaskId publication target kind=${target.mediaKind.name} " +
                "actualName=${target.actualDisplayName} uri=${target.mediaStoreUri}",
        )
        onOutputFileName(target.actualDisplayName)
    }

    override fun onPublicationCompleted(target: PublicationTarget) {
        val internalTaskId =
            taskId() ?: throw IllegalStateException("Publication completed without an engine task")
        when (publicationCompletionRecoveryStage(cancellationRequested())) {
            RecoveryStage.PUBLISHED -> journal.markPublished(internalTaskId)
            RecoveryStage.DISCARDING -> journal.markDiscarding(internalTaskId)
            else -> error("Unexpected publication completion stage")
        }
        logger("task=$internalTaskId publication completed uri=${target.mediaStoreUri}")
    }

    override fun onPublicationDiscarding(target: PublicationTarget) {
        val internalTaskId =
            taskId() ?: throw IllegalStateException("Publication discarded without an engine task")
        journal.markDiscarding(internalTaskId)
        logger("task=$internalTaskId publication discarding uri=${target.mediaStoreUri}")
    }
}

internal class ProcessingService : Service() {
    private lateinit var notificationFactory: ProcessingNotificationFactory
    private lateinit var wakeLockGuard: WakeLockGuard
    private lateinit var transcodeEngine: TranscodeEngine
    private lateinit var audioExtractionEngine: AudioExtractionEngine
    private lateinit var recoveryStore: TaskRecoveryStore
    private lateinit var logDispatcher: AppLogDispatcher
    @Volatile private var activeTaskId: String? = null
    @Volatile private var engineTaskId: String? = null
    @Volatile private var terminalHandled = false
    @Volatile private var timeoutRequested = false
    @Volatile private var cancelRequested = false
    private var lastStartId = 0

    private val registryObserver: (TaskRuntimeSnapshot) -> Unit = { snapshot ->
        if (
            snapshot.taskId == activeTaskId &&
            snapshot.state == TaskRuntimeSnapshot.STATE_RUNNING
        ) {
            runCatching {
                notificationFactory.notifyForeground(
                    snapshot,
                    cancelPendingIntent(snapshot.taskId),
                )
            }.onFailure { error -> log("notification update failed: ${error.stackTraceToString()}") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationFactory = ProcessingNotificationFactory(this)
        wakeLockGuard = WakeLockGuard(AndroidPartialWakeLock(this))
        logDispatcher = (application as VideoSlimApplication).logDispatcher
        recoveryStore = TaskRecoveryStore(this, ::log)
        runCatching { OrphanCleanup(this, recoveryStore, ::log).reconcile() }
            .onFailure { error ->
                log("service startup reconciliation failed ${error.stackTraceToString()}")
            }
        val publicationObserver =
            RecoveryPublicationObserver(
                journal = recoveryStore,
                taskId = { engineTaskId },
                cancellationRequested = { cancelRequested || timeoutRequested },
                onOutputFileName = { actualDisplayName ->
                    activeTaskId?.let { publicTaskId ->
                        ProcessingRuntime.registry.updateOutputFileName(
                            publicTaskId,
                            actualDisplayName,
                        )
                    }
                },
                logger = ::log,
            )
        val mediaStoreSaver = MediaStoreSaver(this, publicationObserver)
        transcodeEngine =
            TranscodeEngine(
                context = this,
                mediaStoreSaver = mediaStoreSaver,
                recoveryStore = recoveryStore,
                logger = ::log,
            )
        audioExtractionEngine =
            AudioExtractionEngine(
                context = this,
                mediaStoreSaver = mediaStoreSaver,
                recoveryStore = recoveryStore,
                logger = ::log,
            )
        ProcessingRuntime.registry.addObserver(registryObserver)
        log("processing service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> handleCancel(intent.getStringExtra(EXTRA_TASK_ID))
            else -> {
                log("processing service ignored missing or unknown action")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        log("processing service timeout startId=$startId type=$fgsType")
        timeoutRequested = true
        val internalTaskId = engineTaskId
        if (internalTaskId == null) {
            failActiveTask("系统已结束超时的媒体处理任务")
            return
        }
        runCatching { cancelEngineTask(internalTaskId) }
            .onFailure { log("timeout cancellation failed: ${it.stackTraceToString()}") }
        runCatching {
            val recovery = recoveryStore.load()
            if (
                recovery?.taskId == internalTaskId &&
                (recovery.stage == RecoveryStage.PUBLISHING ||
                    recovery.stage == RecoveryStage.PUBLISHED)
            ) {
                recoveryStore.markDiscarding(internalTaskId)
            }
        }.onFailure { log("timeout discard boundary failed: ${it.stackTraceToString()}") }
        if (!terminalHandled) {
            failActiveTask("系统已结束超时的媒体处理任务")
        }
    }

    override fun onDestroy() {
        ProcessingRuntime.registry.removeObserver(registryObserver)
        if (!terminalHandled) {
            val taskId = activeTaskId
            val snapshot = ProcessingRuntime.registry.snapshot()
            if (
                taskId != null &&
                snapshot?.taskId == taskId &&
                !snapshot.isTerminal
            ) {
                ProcessingRuntime.registry.apply(
                    taskId = taskId,
                    percent = snapshot.percent,
                    state = TaskRuntimeSnapshot.STATE_FAILED,
                    errorCode = EngineErrorCode.UNKNOWN.wireName,
                    errorMessage = "媒体处理服务已意外终止",
                )
            }
        }
        runCatching { transcodeEngine.dispose() }
            .onFailure { log("engine disposal failed: ${it.stackTraceToString()}") }
        runCatching { audioExtractionEngine.dispose() }
            .onFailure { log("audio engine disposal failed: ${it.stackTraceToString()}") }
        wakeLockGuard.releaseAll()
        log("processing service destroyed")
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val snapshot = ProcessingRuntime.registry.snapshot()
        if (
            taskId.isNullOrBlank() ||
            snapshot == null ||
            snapshot.taskId != taskId ||
            snapshot.isTerminal
        ) {
            log("processing start rejected because reservation is missing")
            stopSelfResult(lastStartId)
            return
        }
        val startTaskKind =
            runCatching { validatedStartTaskKind(snapshot, intent.getStringExtra(EXTRA_TASK_KIND)) }
                .getOrElse { error ->
                    log("processing start rejected because task kind is invalid: ${error.message}")
                    ProcessingRuntime.registry.apply(
                        taskId = taskId,
                        percent = snapshot.percent,
                        state = TaskRuntimeSnapshot.STATE_FAILED,
                        errorCode = EngineErrorCode.UNKNOWN.wireName,
                        errorMessage = "媒体处理任务类型无效，请重试",
                    )
                    stopSelfResult(lastStartId)
                    return
                }
        if (activeTaskId != null) {
            if (activeTaskId != taskId) {
                ProcessingRuntime.registry.apply(
                    taskId = taskId,
                    percent = snapshot.percent,
                    state = TaskRuntimeSnapshot.STATE_FAILED,
                    errorCode = EngineErrorCode.UNKNOWN.wireName,
                    errorMessage = "已有媒体处理任务正在进行中",
                )
            }
            return
        }

        activeTaskId = taskId
        try {
            startForegroundCompat(snapshot, cancelPendingIntent(taskId))
            wakeLockGuard.acquire(taskId, MAX_WAKE_LOCK_MS)
            val createdEngineTaskId =
                when (startTaskKind) {
                    TaskKind.VIDEO_COMPRESSION -> {
                        val request = ProcessRequest.parse(readArguments(intent))
                        transcodeEngine.start(request) { event -> onEngineEvent(taskId, event) }
                    }
                    TaskKind.AUDIO_EXTRACTION -> {
                        val request = AudioExtractRequest.parse(readArguments(intent))
                        audioExtractionEngine.start(request) { event -> onEngineEvent(taskId, event) }
                    }
                }
            engineTaskId = createdEngineTaskId
            log(
                "task=$taskId taskKind=${startTaskKind.wireName} " +
                    "engineTask=$createdEngineTaskId service processing started",
            )
            if (cancelRequested) cancelEngineTask(createdEngineTaskId)
        } catch (error: ProcessRequestException) {
            failActiveTask(error.error.message, error.error.code.wireName)
        } catch (error: EngineOperationException) {
            failActiveTask(error.failure.message, error.failure.code.wireName)
        } catch (error: Throwable) {
            val failure = EngineErrorMapper.fromThrowable(error)
            log("processing launch failed: ${error.stackTraceToString()}")
            failActiveTask(failure.message, failure.code.wireName)
        }
    }

    private fun handleCancel(taskId: String?) {
        val currentTaskId = activeTaskId
        if (taskId.isNullOrBlank() || currentTaskId == null || taskId != currentTaskId) {
            log("ignored cancellation for non-active task=$taskId")
            if (currentTaskId == null) stopSelfResult(lastStartId)
            return
        }
        cancelRequested = true
        val internalTaskId = engineTaskId ?: return
        markDiscardingForAcceptedCancellation(internalTaskId)
        runCatching { cancelEngineTask(internalTaskId) }
            .onFailure { error ->
                if (error is EngineOperationException && error.failure.code == EngineErrorCode.CANCELLED) {
                    log("task=$taskId cancellation already terminal")
                } else {
                    log("task=$taskId cancellation failed: ${error.stackTraceToString()}")
                }
            }
    }

    private fun markDiscardingForAcceptedCancellation(internalTaskId: String) {
        runCatching {
            val recovery = recoveryStore.load()
            if (
                recovery?.taskId == internalTaskId &&
                recovery.stage in setOf(RecoveryStage.PUBLISHING, RecoveryStage.PUBLISHED)
            ) {
                recoveryStore.markDiscarding(internalTaskId)
            }
        }.onFailure { error ->
            log("task=$internalTaskId cancellation discard boundary failed: ${error.stackTraceToString()}")
        }
    }

    private fun cancelEngineTask(internalTaskId: String) {
        when (ProcessingRuntime.registry.snapshot()?.taskKind) {
            TaskKind.AUDIO_EXTRACTION -> audioExtractionEngine.cancel(internalTaskId)
            else -> transcodeEngine.cancel(internalTaskId)
        }
    }

    private fun onEngineEvent(
        publicTaskId: String,
        event: EngineProgressEvent,
    ) {
        if (publicTaskId != activeTaskId || terminalHandled) return
        val currentEngineTaskId = engineTaskId
        if (currentEngineTaskId != null && event.taskId != currentEngineTaskId) return
        val state =
            if (timeoutRequested && event.state == TaskRuntimeSnapshot.STATE_CANCELLED) {
                TaskRuntimeSnapshot.STATE_FAILED
            } else {
                event.state
            }
        val accepted =
            ProcessingRuntime.registry.apply(
                taskId = publicTaskId,
                percent = event.percent,
                state = state,
                phase = event.phase,
                actualVideoEncodingMode = event.actualVideoEncodingMode.wireName,
                outputUri = event.outputUri,
                errorCode =
                    if (timeoutRequested && state == TaskRuntimeSnapshot.STATE_FAILED) {
                        EngineErrorCode.UNKNOWN.wireName
                    } else {
                        event.errorCode
                    },
                errorMessage =
                    if (timeoutRequested && state == TaskRuntimeSnapshot.STATE_FAILED) {
                        "系统已结束超时的媒体处理任务"
                    } else {
                        event.errorMessage
                    },
            )
        if (!accepted || state == TaskRuntimeSnapshot.STATE_RUNNING) return
        ProcessingRuntime.registry.snapshot()?.let(::finishTerminal)
    }

    private fun failActiveTask(
        message: String,
        errorCode: String = EngineErrorCode.UNKNOWN.wireName,
    ) {
        val taskId = activeTaskId ?: return
        val snapshot = ProcessingRuntime.registry.snapshot() ?: return
        ProcessingRuntime.registry.apply(
            taskId = taskId,
            percent = snapshot.percent,
            state = TaskRuntimeSnapshot.STATE_FAILED,
            errorCode = errorCode,
            errorMessage = message,
        )
        ProcessingRuntime.registry.snapshot()?.let(::finishTerminal)
    }

    private fun finishTerminal(snapshot: TaskRuntimeSnapshot) {
        if (terminalHandled || !snapshot.isTerminal || snapshot.taskId != activeTaskId) return
        terminalHandled = true
        wakeLockGuard.release(snapshot.taskId)
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationFactory.notifyTerminal(snapshot)
        }.onFailure { error -> log("terminal notification failed: ${error.stackTraceToString()}") }
        stopSelfResult(lastStartId)
        log("task=${snapshot.taskId} service terminal state=${snapshot.state}")
    }

    private fun startForegroundCompat(
        snapshot: TaskRuntimeSnapshot,
        cancelIntent: PendingIntent,
    ) {
        val notification = notificationFactory.foreground(snapshot, cancelIntent)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                startForeground(
                    ProcessingNotificationFactory.FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(
                    ProcessingNotificationFactory.FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )

            else ->
                startForeground(
                    ProcessingNotificationFactory.FOREGROUND_NOTIFICATION_ID,
                    notification,
                )
        }
    }

    private fun cancelPendingIntent(taskId: String): PendingIntent =
        PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            Intent(this, ProcessingService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @Suppress("DEPRECATION")
    private fun readArguments(intent: Intent): Any? = intent.getSerializableExtra(EXTRA_ARGUMENTS)

    private fun log(message: String) {
        logDispatcher.native(message) { outcome ->
            outcome.exceptionOrNull()?.let {
                (application as VideoSlimApplication).logNativeFailure(message, it)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.videoslim.videoslim.action.PROCESS_START"
        const val ACTION_CANCEL = "com.videoslim.videoslim.action.PROCESS_CANCEL"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TASK_KIND = "taskKind"
        const val EXTRA_ARGUMENTS = "arguments"
        private const val CANCEL_REQUEST_CODE = 2_004
        private const val MAX_WAKE_LOCK_MS = 21_900_000L
    }
}

internal fun publicationCompletionRecoveryStage(cancellationRequested: Boolean): RecoveryStage =
    if (cancellationRequested) RecoveryStage.DISCARDING else RecoveryStage.PUBLISHED
