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
    @Volatile private var activeTaskContext: ActiveTaskContext? = null
    @Volatile private var registryObserverRegistered = false
    @Volatile private var launchGeneration = 0L
    private var lastStartId = 0

    private val registryObserver: (TaskRuntimeSnapshot) -> Unit = { snapshot ->
        val context = activeTaskContext
        if (
            context != null &&
            context.owns(snapshot.taskId, launchGeneration) &&
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
                taskId = { activeTaskContext?.engineTaskId },
                cancellationRequested = { activeTaskContext?.isCancellationRequested == true },
                onOutputFileName = { actualDisplayName ->
                    activeTaskContext?.serviceTaskId?.let { publicTaskId ->
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
        registryObserverRegistered = true
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
        val context = activeTaskContext ?: return
        when (
            val decision =
                context.requestCancellation(
                    taskId = context.serviceTaskId,
                    generation = context.launchGeneration,
                    source = ActiveTaskCancellationSource.TIMEOUT,
                )
        ) {
            ActiveTaskCancellationDecision.Ignored -> return
            ActiveTaskCancellationDecision.FinishBeforeEngine -> Unit
            is ActiveTaskCancellationDecision.CancelEngine -> {
                markDiscardingForAcceptedCancellation(decision.route.engineTaskId)
                runCatching { cancelEngineTask(decision.route) }
                    .onFailure { log("timeout cancellation failed: ${it.stackTraceToString()}") }
            }
        }
        finishActiveTask(
            context = context,
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.CANCEL_TIMEOUT,
            publishTerminal = {
                publishFailure(
                    context,
                    message = "系统已结束超时的媒体处理任务",
                )
            },
        )
    }

    override fun onDestroy() {
        try {
            activeTaskContext?.let { context ->
                bestEffortCleanup("task=${context.serviceTaskId} destruction terminal handling") {
                    context.requestCancellation(
                        taskId = context.serviceTaskId,
                        generation = context.launchGeneration,
                        source = ActiveTaskCancellationSource.SERVICE_DESTROY,
                    )
                    finishActiveTask(
                        context = context,
                        outcome = ActiveTaskTerminalOutcome.FAILED,
                        source = ActiveTaskFinishSource.ON_DESTROY,
                        publishTerminal = {
                            publishFailure(
                                context,
                                message = "媒体处理服务已意外终止",
                            )
                        },
                    )
                }
            }
            if (registryObserverRegistered) {
                registryObserverRegistered = false
                bestEffortCleanup("registry observer removal") {
                    ProcessingRuntime.registry.removeObserver(registryObserver)
                }
            }
            bestEffortCleanup("engine disposal") { transcodeEngine.dispose() }
            bestEffortCleanup("audio engine disposal") { audioExtractionEngine.dispose() }
            bestEffortCleanup("wake-lock safety release") { wakeLockGuard.releaseAll() }
            runCatching { log("processing service destroyed") }
        } finally {
            super.onDestroy()
        }
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
        val existingContext = activeTaskContext
        if (existingContext != null) {
            if (existingContext.serviceTaskId != taskId) {
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

        val generation = ++launchGeneration
        val context =
            ActiveTaskContext(
                serviceTaskId = taskId,
                taskKind = startTaskKind,
                launchGeneration = generation,
            )
        activeTaskContext = context
        if (!registryObserverRegistered) {
            ProcessingRuntime.registry.addObserver(registryObserver)
            registryObserverRegistered = true
        }
        try {
            startForegroundCompat(snapshot, cancelPendingIntent(taskId))
            wakeLockGuard.acquire(taskId, MAX_WAKE_LOCK_MS)
            if (!context.canLaunch(generation)) {
                finishCancellationBeforeEngine(context)
                return
            }
            val createdEngineTaskId =
                when (context.taskKind) {
                    TaskKind.VIDEO_COMPRESSION -> {
                        val request = ProcessRequest.parse(readArguments(intent))
                        transcodeEngine.start(request) { event ->
                            onEngineEvent(context, generation, event)
                        }
                    }
                    TaskKind.AUDIO_EXTRACTION -> {
                        val request = AudioExtractRequest.parse(readArguments(intent))
                        audioExtractionEngine.start(request) { event ->
                            onEngineEvent(context, generation, event)
                        }
                    }
                }
            val route = context.assignEngineTaskId(generation, createdEngineTaskId)
            if (route == null) {
                if (context.isCancellationRequested) {
                    context.routeForStartedEngine(generation, createdEngineTaskId)?.let { lateRoute ->
                        markDiscardingForAcceptedCancellation(lateRoute.engineTaskId)
                        runCatching { cancelEngineTask(lateRoute) }
                            .onFailure { error ->
                                log("task=$taskId late cancellation failed: ${error.stackTraceToString()}")
                            }
                    }
                    finishCancellationBeforeEngine(context)
                }
                return
            }
            log(
                "task=$taskId taskKind=${context.taskKind.wireName} " +
                    "engineTask=${route.engineTaskId} service processing started",
            )
        } catch (error: ProcessRequestException) {
            finishStartFailure(context, error.error.message, error.error.code.wireName)
        } catch (error: EngineOperationException) {
            finishStartFailure(context, error.failure.message, error.failure.code.wireName)
        } catch (error: Throwable) {
            val failure = EngineErrorMapper.fromThrowable(error)
            log("processing launch failed: ${error.stackTraceToString()}")
            finishStartFailure(context, failure.message, failure.code.wireName)
        }
    }

    private fun handleCancel(taskId: String?) {
        val context = activeTaskContext
        if (taskId.isNullOrBlank() || context == null || taskId != context.serviceTaskId) {
            log("ignored cancellation for non-active task=$taskId")
            if (context == null) stopSelfResult(lastStartId)
            return
        }
        when (
            val decision =
                context.requestCancellation(
                    taskId = taskId,
                    generation = context.launchGeneration,
                    source = ActiveTaskCancellationSource.USER,
                )
        ) {
            ActiveTaskCancellationDecision.Ignored -> return
            ActiveTaskCancellationDecision.FinishBeforeEngine -> finishCancellationBeforeEngine(context)
            is ActiveTaskCancellationDecision.CancelEngine -> {
                markDiscardingForAcceptedCancellation(decision.route.engineTaskId)
                runCatching { cancelEngineTask(decision.route) }
                    .onFailure { error ->
                        if (
                            error is EngineOperationException &&
                            error.failure.code == EngineErrorCode.CANCELLED
                        ) {
                            log("task=$taskId cancellation already terminal")
                        } else {
                            log("task=$taskId cancellation failed: ${error.stackTraceToString()}")
                        }
                    }
            }
        }
    }

    private fun finishCancellationBeforeEngine(context: ActiveTaskContext) {
        if (context.cancellationSource == ActiveTaskCancellationSource.TIMEOUT) {
            finishActiveTask(
                context = context,
                outcome = ActiveTaskTerminalOutcome.FAILED,
                source = ActiveTaskFinishSource.CANCEL_TIMEOUT,
                publishTerminal = {
                    publishFailure(
                        context,
                        message = "系统已结束超时的媒体处理任务",
                    )
                },
            )
        } else {
            finishActiveTask(
                context = context,
                outcome = ActiveTaskTerminalOutcome.CANCELLED,
                source = ActiveTaskFinishSource.CANCEL_BEFORE_ENGINE,
                publishTerminal = { publishCancellation(context) },
            )
        }
    }

    private fun finishStartFailure(
        context: ActiveTaskContext,
        message: String,
        errorCode: String,
    ) {
        finishActiveTask(
            context = context,
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.START_EXCEPTION,
            publishTerminal = { publishFailure(context, message, errorCode) },
        )
    }

    private fun publishFailure(
        context: ActiveTaskContext,
        message: String,
        errorCode: String = EngineErrorCode.UNKNOWN.wireName,
    ) {
        val snapshot = ProcessingRuntime.registry.snapshot()
        if (snapshot?.taskId != context.serviceTaskId || snapshot.isTerminal) return
        ProcessingRuntime.registry.apply(
            taskId = context.serviceTaskId,
            percent = snapshot.percent,
            state = TaskRuntimeSnapshot.STATE_FAILED,
            errorCode = errorCode,
            errorMessage = message,
        )
    }

    private fun publishCancellation(context: ActiveTaskContext) {
        val snapshot = ProcessingRuntime.registry.snapshot()
        if (snapshot?.taskId != context.serviceTaskId || snapshot.isTerminal) return
        ProcessingRuntime.registry.apply(
            taskId = context.serviceTaskId,
            percent = snapshot.percent,
            state = TaskRuntimeSnapshot.STATE_CANCELLED,
        )
    }

    private fun finishActiveTask(
        context: ActiveTaskContext,
        outcome: ActiveTaskTerminalOutcome,
        source: ActiveTaskFinishSource,
        publishTerminal: () -> Unit = {},
    ) {
        try {
            val decision =
                context.finishOnce(
                    generation = context.launchGeneration,
                    outcome = outcome,
                    source = source,
                    onWinner = publishTerminal,
                    releaseResources = {
                        releaseActiveTaskResources(
                            context = context,
                            stopService = source != ActiveTaskFinishSource.ON_DESTROY,
                        )
                    },
                )
            if (decision is ActiveTaskFinishDecision.Won) {
                log(
                    "task=${context.serviceTaskId} service terminal " +
                        "outcome=${outcome.name.lowercase()} source=${source.name.lowercase()}",
                )
            }
        } catch (error: Throwable) {
            runCatching {
                log(
                    "task=${context.serviceTaskId} terminal handling failed: " +
                        error.stackTraceToString(),
                )
            }
        }
    }

    private fun releaseActiveTaskResources(
        context: ActiveTaskContext,
        stopService: Boolean,
    ) {
        val ownsService = activeTaskContext === context && context.launchGeneration == launchGeneration
        if (ownsService && registryObserverRegistered) {
            registryObserverRegistered = false
            bestEffortCleanup("task=${context.serviceTaskId} registry observer removal") {
                ProcessingRuntime.registry.removeObserver(registryObserver)
            }
        }
        bestEffortCleanup("task=${context.serviceTaskId} wake-lock release") {
            wakeLockGuard.release(context.serviceTaskId)
        }
        if (!ownsService) return

        try {
            bestEffortCleanup("task=${context.serviceTaskId} foreground release") {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            bestEffortCleanup("task=${context.serviceTaskId} terminal notification") {
                matchingTerminalSnapshot(context)?.let { snapshot ->
                    notificationFactory.notifyTerminal(snapshot)
                }
            }
        } finally {
            activeTaskContext = null
            if (stopService) {
                bestEffortCleanup("task=${context.serviceTaskId} service stop") {
                    stopSelfResult(lastStartId)
                }
            }
        }
    }

    private fun matchingTerminalSnapshot(context: ActiveTaskContext): TaskRuntimeSnapshot? =
        ProcessingRuntime.registry.snapshot()
            ?.takeIf { snapshot ->
                snapshot.taskId == context.serviceTaskId && snapshot.isTerminal
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

    private fun cancelEngineTask(route: EngineTaskRoute) {
        when (route.taskKind) {
            TaskKind.VIDEO_COMPRESSION -> transcodeEngine.cancel(route.engineTaskId)
            TaskKind.AUDIO_EXTRACTION -> audioExtractionEngine.cancel(route.engineTaskId)
        }
    }

    private fun onEngineEvent(
        context: ActiveTaskContext,
        generation: Long,
        event: EngineProgressEvent,
    ) {
        if (activeTaskContext !== context || !context.acceptsEngineEvent(generation, event.taskId)) return
        val timeoutRequested = context.cancellationSource == ActiveTaskCancellationSource.TIMEOUT
        val state =
            if (timeoutRequested && event.state == TaskRuntimeSnapshot.STATE_CANCELLED) {
                TaskRuntimeSnapshot.STATE_FAILED
            } else {
                event.state
            }
        val publishEvent: () -> Unit = {
            ProcessingRuntime.registry.apply(
                taskId = context.serviceTaskId,
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
        }
        if (state == TaskRuntimeSnapshot.STATE_RUNNING) {
            publishEvent()
            return
        }
        finishActiveTask(
            context = context,
            outcome =
                when (state) {
                    TaskRuntimeSnapshot.STATE_SUCCESS -> ActiveTaskTerminalOutcome.SUCCEEDED
                    TaskRuntimeSnapshot.STATE_CANCELLED -> ActiveTaskTerminalOutcome.CANCELLED
                    else -> ActiveTaskTerminalOutcome.FAILED
                },
            source = ActiveTaskFinishSource.ENGINE_TERMINAL,
            publishTerminal = publishEvent,
        )
    }

    private inline fun bestEffortCleanup(
        description: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Throwable) {
            runCatching { log("$description failed: ${error.stackTraceToString()}") }
        }
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
