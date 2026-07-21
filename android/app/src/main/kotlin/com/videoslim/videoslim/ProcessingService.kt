package com.videoslim.videoslim

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

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
    private val owner: ActiveTaskPublicationOwner,
    private val onOutputFileName: (PublicationLaunchIdentity, String) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = {},
) : PublicationObserver {
    override fun onPublicationUriAllocated(publicationUri: String) {
        val identity = identityOrIgnore("allocation") ?: return
        val internalTaskId = identity.engineTaskId
        journal.recordPublicationAllocation(internalTaskId, publicationUri)
        logger("task=$internalTaskId publication allocated uri=$publicationUri")
    }

    override fun onPublicationTargetAllocated(target: PublicationTarget) {
        val identity = identityOrIgnore("target allocation") ?: return
        val internalTaskId = identity.engineTaskId
        journal.recordPublicationTarget(
            taskId = internalTaskId,
            actualOutputDisplayName = target.actualDisplayName,
            mediaStoreUri = target.mediaStoreUri,
            canonicalLegacyOutputPath = target.canonicalLegacyOutputPath,
            mediaKind = target.mediaKind,
        )
        if (owner.isCancellationRequested) journal.markDiscarding(internalTaskId)
        logger(
            "task=$internalTaskId publication target kind=${target.mediaKind.name} " +
                "actualName=${target.actualDisplayName} uri=${target.mediaStoreUri}",
        )
        onOutputFileName(identity, target.actualDisplayName)
    }

    override fun onPublicationCompleted(target: PublicationTarget) {
        val identity = identityOrIgnore("completion") ?: return
        val internalTaskId = identity.engineTaskId
        when (publicationCompletionRecoveryStage(owner.isCancellationRequested)) {
            RecoveryStage.PUBLISHED -> journal.markPublished(internalTaskId)
            RecoveryStage.DISCARDING -> journal.markDiscarding(internalTaskId)
            else -> error("Unexpected publication completion stage")
        }
        logger("task=$internalTaskId publication completed uri=${target.mediaStoreUri}")
    }

    override fun onPublicationDiscarding(target: PublicationTarget) {
        val identity = identityOrIgnore("discard") ?: return
        val internalTaskId = identity.engineTaskId
        journal.markDiscarding(internalTaskId)
        logger("task=$internalTaskId publication discarding uri=${target.mediaStoreUri}")
    }

    private fun identityOrIgnore(callback: String): PublicationLaunchIdentity? =
        owner.identity.also { identity ->
            if (identity == null) {
                logger("ignored publication $callback before launch engine ownership was bound")
            }
        }
}

private data class ActiveServiceLaunch(
    val context: ActiveTaskContext,
    val publicationOwner: ActiveTaskPublicationOwner,
    val transcodeEngine: TranscodeEngine,
    val audioExtractionEngine: AudioExtractionEngine,
    val terminationPolicy: ServiceTerminationPolicy,
)

internal class ProcessingService : Service() {
    private lateinit var notificationFactory: ProcessingNotificationFactory
    private lateinit var wakeLockGuard: WakeLockGuard
    private lateinit var recoveryStore: TaskRecoveryStore
    private lateinit var logDispatcher: AppLogDispatcher
    private lateinit var mainHandler: Handler
    @Volatile private var activeLaunch: ActiveServiceLaunch? = null
    @Volatile private var latestLaunch: ActiveServiceLaunch? = null
    @Volatile private var registryObserverRegistered = false
    @Volatile private var launchGeneration = 0L
    private var lastStartId = 0

    private val registryObserver: (TaskRuntimeSnapshot) -> Unit = { snapshot ->
        val context = activeLaunch?.context
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
        mainHandler = Handler(Looper.getMainLooper())
        logDispatcher = (application as VideoSlimApplication).logDispatcher
        recoveryStore = TaskRecoveryStore(this, ::log)
        runCatching { OrphanCleanup(this, recoveryStore, ::log).reconcile() }
            .onFailure { error ->
                log("service startup reconciliation failed ${error.stackTraceToString()}")
            }
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
        activeLaunch?.terminationPolicy?.onTimeout()
    }

    override fun onDestroy() {
        try {
            latestLaunch?.terminationPolicy?.onDestroy()
            latestLaunch = null
            if (registryObserverRegistered) {
                registryObserverRegistered = false
                bestEffortCleanup("registry observer removal") {
                    ProcessingRuntime.registry.removeObserver(registryObserver)
                }
            }
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
        val existingLaunch = activeLaunch
        if (existingLaunch != null) {
            if (existingLaunch.context.serviceTaskId != taskId) {
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
        val launch = createServiceLaunch(context)
        activeLaunch = launch
        latestLaunch = launch
        if (!registryObserverRegistered) {
            ProcessingRuntime.registry.addObserver(registryObserver)
            registryObserverRegistered = true
        }
        try {
            startForegroundCompat(snapshot, cancelPendingIntent(taskId))
            wakeLockGuard.acquire(taskId, MAX_WAKE_LOCK_MS)
            if (!context.canLaunch(generation)) {
                launch.terminationPolicy.finishCancellationBeforeEngine()
                return
            }
            val createdEngineTaskId =
                when (context.taskKind) {
                    TaskKind.VIDEO_COMPRESSION -> {
                        val request = ProcessRequest.parse(readArguments(intent))
                        launch.transcodeEngine.start(request) { event ->
                            onEngineEvent(launch, generation, event)
                        }
                    }
                    TaskKind.AUDIO_EXTRACTION -> {
                        val request = AudioExtractRequest.parse(readArguments(intent))
                        launch.audioExtractionEngine.start(request) { event ->
                            onEngineEvent(launch, generation, event)
                        }
                    }
                }
            val route = context.assignEngineTaskId(generation, createdEngineTaskId)
            if (route == null) {
                if (context.isCancellationRequested) {
                    context.routeForStartedEngine(generation, createdEngineTaskId)?.let { lateRoute ->
                        launch.publicationOwner.bindEngineRoute(lateRoute)
                        launch.terminationPolicy.cancelLateStartedEngine(lateRoute)
                    }
                    launch.terminationPolicy.finishCancellationBeforeEngine()
                }
                return
            }
            check(launch.publicationOwner.bindEngineRoute(route)) {
                "Publication owner rejected its launch engine route"
            }
            log(
                "task=$taskId taskKind=${context.taskKind.wireName} " +
                    "engineTask=${route.engineTaskId} service processing started",
            )
        } catch (error: ProcessRequestException) {
            finishStartFailure(launch, error.error.message, error.error.code.wireName)
        } catch (error: EngineOperationException) {
            finishStartFailure(launch, error.failure.message, error.failure.code.wireName)
        } catch (error: Throwable) {
            val failure = EngineErrorMapper.fromThrowable(error)
            log("processing launch failed: ${error.stackTraceToString()}")
            finishStartFailure(launch, failure.message, failure.code.wireName)
        }
    }

    private fun handleCancel(taskId: String?) {
        val launch = activeLaunch
        val context = launch?.context
        if (taskId.isNullOrBlank() || launch == null || context == null || taskId != context.serviceTaskId) {
            log("ignored cancellation for non-active task=$taskId")
            if (context == null) stopSelfResult(lastStartId)
            return
        }
        launch.terminationPolicy.handleCancel(taskId)
    }

    private fun finishStartFailure(
        launch: ActiveServiceLaunch,
        message: String,
        errorCode: String,
    ) {
        finishActiveTask(
            launch = launch,
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.START_EXCEPTION,
            errorCode = errorCode,
            errorMessage = message,
        )
    }

    private fun publishTerminalDirective(
        context: ActiveTaskContext,
        terminal: ServiceTerminalDirective,
    ) {
        val snapshot = ProcessingRuntime.registry.snapshot()
        if (snapshot?.taskId != context.serviceTaskId || snapshot.isTerminal) return
        when (terminal.outcome) {
            ActiveTaskTerminalOutcome.FAILED ->
                ProcessingRuntime.registry.apply(
                    taskId = context.serviceTaskId,
                    percent = snapshot.percent,
                    state = TaskRuntimeSnapshot.STATE_FAILED,
                    errorCode = terminal.errorCode ?: EngineErrorCode.UNKNOWN.wireName,
                    errorMessage = terminal.errorMessage ?: "媒体处理任务未能完成",
                )
            ActiveTaskTerminalOutcome.CANCELLED ->
                ProcessingRuntime.registry.apply(
                    taskId = context.serviceTaskId,
                    percent = snapshot.percent,
                    state = TaskRuntimeSnapshot.STATE_CANCELLED,
                )
            ActiveTaskTerminalOutcome.SUCCEEDED -> Unit
        }
    }

    private fun finishActiveTask(
        launch: ActiveServiceLaunch,
        outcome: ActiveTaskTerminalOutcome,
        source: ActiveTaskFinishSource,
        errorCode: String? = null,
        errorMessage: String? = null,
        publishTerminal: (() -> Unit)? = null,
    ) {
        val context = launch.context
        try {
            val terminal =
                ServiceTerminalDirective(
                    outcome = outcome,
                    source = source,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            val decision =
                launch.terminationPolicy.finish(
                    terminal = terminal,
                    publishRegistryTerminal = publishTerminal,
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

    private fun createServiceLaunch(context: ActiveTaskContext): ActiveServiceLaunch {
        val publicationOwner = ActiveTaskPublicationOwner(context)
        val publicationObserver =
            RecoveryPublicationObserver(
                journal = recoveryStore,
                owner = publicationOwner,
                onOutputFileName = { identity, actualDisplayName ->
                    val currentContext = activeLaunch?.context
                    if (
                        currentContext === context &&
                        context.owns(identity.serviceTaskId, identity.launchGeneration)
                    ) {
                        ProcessingRuntime.registry.updateOutputFileName(
                            identity.serviceTaskId,
                            actualDisplayName,
                        )
                    } else {
                        log(
                            "task=${identity.engineTaskId} ignored stale publication filename " +
                                "generation=${identity.launchGeneration}",
                        )
                    }
                },
                logger = ::log,
            )
        val mediaStoreSaver = MediaStoreSaver(this, publicationObserver)
        val transcodeEngine =
            TranscodeEngine(
                context = this,
                mediaStoreSaver = mediaStoreSaver,
                recoveryStore = recoveryStore,
                logger = ::log,
            )
        val audioExtractionEngine =
            AudioExtractionEngine(
                context = this,
                mediaStoreSaver = mediaStoreSaver,
                recoveryStore = recoveryStore,
                logger = ::log,
            )
        val cancellationWatchdog =
            UserCancellationWatchdog(
                scheduler =
                    CancellationWatchdogScheduler { timeoutMillis, action ->
                        val runnable = Runnable(action)
                        check(mainHandler.postDelayed(runnable, timeoutMillis)) {
                            "Unable to schedule user cancellation watchdog"
                        }
                        CancellationWatchdogRegistration {
                            mainHandler.removeCallbacks(runnable)
                        }
                    },
                timeoutMillis = USER_CANCELLATION_TIMEOUT_MS,
            )
        val terminalNotifications =
            ServiceTerminalNotificationPolicy(
                context = context,
                registrySnapshot = ProcessingRuntime.registry::snapshot,
                notifyTerminal = notificationFactory::notifyTerminal,
            )
        val terminationPolicy =
            ServiceTerminationPolicy(
                context = context,
                cancellationWatchdog = cancellationWatchdog,
                actions =
                    ServiceTerminationPolicyActions(
                        markPublicationDiscarding = { route ->
                            markDiscardingForAcceptedCancellation(route.engineTaskId)
                        },
                        cancelEngine = { route ->
                            when (route.taskKind) {
                                TaskKind.VIDEO_COMPRESSION -> transcodeEngine.cancel(route.engineTaskId)
                                TaskKind.AUDIO_EXTRACTION -> audioExtractionEngine.cancel(route.engineTaskId)
                            }
                        },
                        publishRegistryTerminal = { terminal ->
                            publishTerminalDirective(context, terminal)
                        },
                        cancelUserWatchdog = cancellationWatchdog::cancel,
                        removeRegistryObserver = {
                            if (registryObserverRegistered) {
                                registryObserverRegistered = false
                                ProcessingRuntime.registry.removeObserver(registryObserver)
                            }
                        },
                        releaseWakeLock = { wakeLockGuard.release(context.serviceTaskId) },
                        removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
                        publishTerminalNotification = terminalNotifications::attempt,
                        disposeTranscodeEngine = transcodeEngine::dispose,
                        disposeAudioExtractionEngine = audioExtractionEngine::dispose,
                        ownsServiceSurface = {
                            activeLaunch?.context === context && context.launchGeneration == launchGeneration
                        },
                        clearActiveLaunch = {
                            if (activeLaunch?.context === context) activeLaunch = null
                        },
                        stopService = { stopSelfResult(lastStartId) },
                        onFailure = { operation, error ->
                            log(
                                "task=${context.serviceTaskId} $operation failed: " +
                                    error.stackTraceToString(),
                            )
                        },
                    ),
            )
        return ActiveServiceLaunch(
            context = context,
            publicationOwner = publicationOwner,
            transcodeEngine = transcodeEngine,
            audioExtractionEngine = audioExtractionEngine,
            terminationPolicy = terminationPolicy,
        )
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


    private fun onEngineEvent(
        launch: ActiveServiceLaunch,
        generation: Long,
        event: EngineProgressEvent,
    ) {
        val context = launch.context
        if (activeLaunch !== launch || !context.acceptsEngineEvent(generation, event.taskId)) return
        val forcedFinishSource =
            when (context.cancellationSource) {
                ActiveTaskCancellationSource.TIMEOUT -> ActiveTaskFinishSource.SERVICE_TIMEOUT
                ActiveTaskCancellationSource.SERVICE_DESTROY -> ActiveTaskFinishSource.ON_DESTROY
                else -> null
            }
        val state =
            if (forcedFinishSource != null && event.state != TaskRuntimeSnapshot.STATE_RUNNING) {
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
                    if (forcedFinishSource != null && state == TaskRuntimeSnapshot.STATE_FAILED) {
                        EngineErrorCode.UNKNOWN.wireName
                    } else {
                        event.errorCode
                    },
                errorMessage =
                    if (forcedFinishSource == ActiveTaskFinishSource.SERVICE_TIMEOUT &&
                        state == TaskRuntimeSnapshot.STATE_FAILED
                    ) {
                        "系统已结束超时的媒体处理任务"
                    } else if (
                        forcedFinishSource == ActiveTaskFinishSource.ON_DESTROY &&
                        state == TaskRuntimeSnapshot.STATE_FAILED
                    ) {
                        "媒体处理服务已意外终止"
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
            launch = launch,
            outcome =
                when (state) {
                    TaskRuntimeSnapshot.STATE_SUCCESS -> ActiveTaskTerminalOutcome.SUCCEEDED
                    TaskRuntimeSnapshot.STATE_CANCELLED -> ActiveTaskTerminalOutcome.CANCELLED
                    else -> ActiveTaskTerminalOutcome.FAILED
                },
            source = forcedFinishSource ?: ActiveTaskFinishSource.ENGINE_TERMINAL,
            errorCode =
                if (state == TaskRuntimeSnapshot.STATE_FAILED) {
                    if (forcedFinishSource != null) EngineErrorCode.UNKNOWN.wireName else event.errorCode
                } else {
                    null
                },
            errorMessage =
                when {
                    state != TaskRuntimeSnapshot.STATE_FAILED -> null
                    forcedFinishSource == ActiveTaskFinishSource.SERVICE_TIMEOUT ->
                        "系统已结束超时的媒体处理任务"
                    forcedFinishSource == ActiveTaskFinishSource.ON_DESTROY ->
                        "媒体处理服务已意外终止"
                    else -> event.errorMessage
                },
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
        private const val USER_CANCELLATION_TIMEOUT_MS = 5_000L
        private const val MAX_WAKE_LOCK_MS = 21_900_000L
    }
}

internal fun publicationCompletionRecoveryStage(cancellationRequested: Boolean): RecoveryStage =
    if (cancellationRequested) RecoveryStage.DISCARDING else RecoveryStage.PUBLISHED
