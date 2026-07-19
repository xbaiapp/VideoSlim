package com.videoslim.videoslim

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal class ProcessingService : Service() {
    private lateinit var notificationFactory: ProcessingNotificationFactory
    private lateinit var wakeLockGuard: WakeLockGuard
    private lateinit var transcodeEngine: TranscodeEngine
    private lateinit var recoveryStore: TaskRecoveryStore
    private lateinit var logStore: AppLogStore
    private val logSequence = AtomicLong()
    @Volatile private var activeTaskId: String? = null
    @Volatile private var engineTaskId: String? = null
    private var terminalHandled = false
    private var timeoutRequested = false
    private var cancelRequested = false
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
        logStore = AppLogStore(this)
        recoveryStore = TaskRecoveryStore(this, ::log)
        runCatching { OrphanCleanup(this, recoveryStore, ::log).reconcile() }
            .onFailure { error ->
                log("service startup reconciliation failed ${error.stackTraceToString()}")
            }
        val publicationObserver =
            object : PublicationObserver {
                override fun onPublicationTargetAllocated(target: PublicationTarget) {
                    val internalTaskId =
                        engineTaskId
                            ?: throw IllegalStateException("Publication started without an engine task")
                    recoveryStore.recordPublicationTarget(
                        taskId = internalTaskId,
                        actualOutputDisplayName = target.actualDisplayName,
                        mediaStoreUri = target.mediaStoreUri,
                        canonicalLegacyOutputPath = target.canonicalLegacyOutputPath,
                    )
                    activeTaskId?.let { publicTaskId ->
                        ProcessingRuntime.registry.updateOutputFileName(
                            publicTaskId,
                            target.actualDisplayName,
                        )
                    }
                }

                override fun onPublicationCompleted(target: PublicationTarget) {
                    val internalTaskId =
                        engineTaskId
                            ?: throw IllegalStateException("Publication completed without an engine task")
                    recoveryStore.markPublished(internalTaskId)
                }

                override fun onPublicationDiscarding(target: PublicationTarget) {
                    val internalTaskId =
                        engineTaskId
                            ?: throw IllegalStateException("Publication discarded without an engine task")
                    recoveryStore.markDiscarding(internalTaskId)
                }
            }
        transcodeEngine =
            TranscodeEngine(
                context = this,
                mediaStoreSaver = MediaStoreSaver(this, publicationObserver),
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
        runCatching { transcodeEngine.cancel(internalTaskId) }
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
        if (activeTaskId != null) {
            if (activeTaskId != taskId) {
                ProcessingRuntime.registry.apply(
                    taskId = taskId,
                    percent = snapshot.percent,
                    state = TaskRuntimeSnapshot.STATE_FAILED,
                    errorCode = EngineErrorCode.UNKNOWN.wireName,
                    errorMessage = "已有视频处理任务正在进行中",
                )
            }
            return
        }

        activeTaskId = taskId
        try {
            startForegroundCompat(snapshot, cancelPendingIntent(taskId))
            wakeLockGuard.acquire(taskId, MAX_WAKE_LOCK_MS)
            val request = ProcessRequest.parse(readArguments(intent))
            val createdEngineTaskId =
                transcodeEngine.start(request) { event -> onEngineEvent(taskId, event) }
            engineTaskId = createdEngineTaskId
            log("task=$taskId engineTask=$createdEngineTaskId service processing started")
            if (cancelRequested) transcodeEngine.cancel(createdEngineTaskId)
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
        runCatching { transcodeEngine.cancel(internalTaskId) }
            .onFailure { error ->
                if (error is EngineOperationException && error.failure.code == EngineErrorCode.CANCELLED) {
                    log("task=$taskId cancellation already terminal")
                } else {
                    log("task=$taskId cancellation failed: ${error.stackTraceToString()}")
                }
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
        runCatching {
            val eventId = "service-${logSequence.incrementAndGet()}"
            logStore.append("${Instant.now()} [INFO] [native] [event:$eventId] $message")
        }
    }

    companion object {
        const val ACTION_START = "com.videoslim.videoslim.action.PROCESS_START"
        const val ACTION_CANCEL = "com.videoslim.videoslim.action.PROCESS_CANCEL"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_ARGUMENTS = "arguments"
        private const val CANCEL_REQUEST_CODE = 2_004
        private const val MAX_WAKE_LOCK_MS = 21_900_000L
    }
}
