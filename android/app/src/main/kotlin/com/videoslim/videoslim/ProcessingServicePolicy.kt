package com.videoslim.videoslim

internal data class PublicationLaunchIdentity(
    val serviceTaskId: String,
    val launchGeneration: Long,
    val engineTaskId: String,
)

internal data class ForegroundNotificationDeliveryCandidate(
    val context: ActiveTaskContext,
    val launchGeneration: Long,
    val taskId: String,
    val snapshot: TaskRuntimeSnapshot,
)

/** Side-effect-free final gate for one foreground-notification delivery queued on service main. */
internal object ForegroundNotificationDeliveryPolicy {
    fun shouldDeliver(
        candidate: ForegroundNotificationDeliveryCandidate,
        activeContext: ActiveTaskContext?,
        registrySnapshot: TaskRuntimeSnapshot?,
        serviceDestroyed: Boolean,
    ): Boolean {
        if (serviceDestroyed) return false
        if (
            candidate.snapshot.taskId != candidate.taskId ||
            candidate.snapshot.taskKind != candidate.context.taskKind ||
            candidate.snapshot.isTerminal
        ) {
            return false
        }
        val currentContext = activeContext ?: return false
        if (currentContext !== candidate.context) return false
        if (
            currentContext.launchGeneration != candidate.launchGeneration ||
            !currentContext.owns(candidate.taskId, candidate.launchGeneration)
        ) {
            return false
        }
        when (currentContext.lifecycle) {
            ActiveTaskLifecycle.AWAITING_ENGINE,
            ActiveTaskLifecycle.ENGINE_ASSIGNED,
            -> Unit
            ActiveTaskLifecycle.FINISHING,
            ActiveTaskLifecycle.RELEASED,
            -> return false
        }
        if (currentContext.terminalOwnership != null) return false
        val currentSnapshot = registrySnapshot ?: return false
        return currentSnapshot == candidate.snapshot &&
            currentSnapshot.taskId == candidate.taskId &&
            !currentSnapshot.isTerminal
    }
}

/** Exact, side-effect-free gate for the single automatic hardware-to-software decoder fallback. */
internal object AutomaticSoftwareDecoderRetryPolicy {
    fun retryRequestOrNull(
        taskKind: TaskKind,
        currentRequest: ProcessRequest?,
        automaticRetryAlreadyAttempted: Boolean,
        cancellationRequested: Boolean,
        forcedFinishSource: ActiveTaskFinishSource?,
        event: EngineProgressEvent,
    ): ProcessRequest? {
        val request = currentRequest ?: return null
        if (
            taskKind != TaskKind.VIDEO_COMPRESSION ||
            request.videoDecoderMode != VideoDecoderMode.HARDWARE ||
            automaticRetryAlreadyAttempted ||
            cancellationRequested ||
            forcedFinishSource != null ||
            event.state != TaskRuntimeSnapshot.STATE_FAILED ||
            event.errorCode != EngineErrorCode.VIDEO_DECODING_FAILED.wireName
        ) {
            return null
        }
        return request.copy(videoDecoderMode = VideoDecoderMode.SOFTWARE)
    }
}

/**
 * Persistent publication identity for one service launch.
 *
 * The owner keeps the assigned engine route after terminal cleanup so publication I/O that is
 * already unwinding can finish journaling against the launch that created it. It never consults
 * the service's mutable active launch.
 */
internal class ActiveTaskPublicationOwner(
    private val context: ActiveTaskContext,
) {
    private val lock = Any()
    private var boundIdentity: PublicationLaunchIdentity? = null

    val identity: PublicationLaunchIdentity?
        get() = synchronized(lock) { boundIdentity }

    val isCancellationRequested: Boolean
        get() = context.isCancellationRequested

    fun bindEngineRoute(route: EngineTaskRoute): Boolean =
        synchronized(lock) {
            if (route.taskKind != context.taskKind) return@synchronized false
            val identity =
                PublicationLaunchIdentity(
                    serviceTaskId = context.serviceTaskId,
                    launchGeneration = context.launchGeneration,
                    engineTaskId = route.engineTaskId,
                )
            boundIdentity?.let { return@synchronized it == identity }
            boundIdentity = identity
            true
        }

    fun replaceEngineRouteForAutomaticRetry(
        previousRoute: EngineTaskRoute,
        retryRoute: EngineTaskRoute,
    ): Boolean =
        synchronized(lock) {
            if (
                previousRoute.taskKind != TaskKind.VIDEO_COMPRESSION ||
                retryRoute.taskKind != TaskKind.VIDEO_COMPRESSION ||
                context.engineRoute != retryRoute
            ) {
                return@synchronized false
            }
            val expectedPrevious =
                PublicationLaunchIdentity(
                    serviceTaskId = context.serviceTaskId,
                    launchGeneration = context.launchGeneration,
                    engineTaskId = previousRoute.engineTaskId,
                )
            if (boundIdentity != expectedPrevious) return@synchronized false
            boundIdentity = expectedPrevious.copy(engineTaskId = retryRoute.engineTaskId)
            true
        }
}

internal fun interface CancellationWatchdogRegistration {
    fun cancel()
}

internal fun interface CancellationWatchdogScheduler {
    fun schedule(
        timeoutMillis: Long,
        action: () -> Unit,
    ): CancellationWatchdogRegistration
}

internal sealed interface UserCancellationWatchdogArmResult {
    data object Armed : UserCancellationWatchdogArmResult

    data object AlreadyArmed : UserCancellationWatchdogArmResult

    data class Rejected(
        val error: Throwable,
    ) : UserCancellationWatchdogArmResult
}

/** One-shot bounded deadline for accepted user cancellation. Scheduler rejection never escapes. */
internal class UserCancellationWatchdog(
    private val scheduler: CancellationWatchdogScheduler,
    private val timeoutMillis: Long,
) {
    private val lock = Any()
    private var armed = false
    private var registration: CancellationWatchdogRegistration? = null

    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }

    fun arm(onTimeout: () -> Unit): UserCancellationWatchdogArmResult {
        synchronized(lock) {
            if (armed) return UserCancellationWatchdogArmResult.AlreadyArmed
            armed = true
        }
        val scheduled =
            try {
                scheduler.schedule(timeoutMillis) {
                    val shouldFire =
                        synchronized(lock) {
                            if (!armed) {
                                false
                            } else {
                                armed = false
                                registration = null
                                true
                            }
                        }
                    if (shouldFire) onTimeout()
                }
            } catch (error: Throwable) {
                synchronized(lock) {
                    armed = false
                    registration = null
                }
                return UserCancellationWatchdogArmResult.Rejected(error)
            }
        val cancelImmediately =
            synchronized(lock) {
                if (!armed) {
                    true
                } else {
                    registration = scheduled
                    false
                }
            }
        if (cancelImmediately) {
            // A scheduler is allowed to invoke synchronously. The timeout already won, so a
            // registration-cancel failure must not turn successful arming into an exception.
            runCatching { scheduled.cancel() }
        }
        return UserCancellationWatchdogArmResult.Armed
    }

    fun cancel() {
        val scheduled =
            synchronized(lock) {
                if (!armed) return
                armed = false
                registration.also { registration = null }
            }
        scheduled?.cancel()
    }
}

internal sealed interface RecoveryWaitWatchdogArmResult {
    data object Armed : RecoveryWaitWatchdogArmResult

    data object AlreadyArmed : RecoveryWaitWatchdogArmResult

    data class Rejected(
        val error: Throwable,
    ) : RecoveryWaitWatchdogArmResult
}

/** A bounded one-shot deadline used only while a launch awaits process reconciliation. */
internal class RecoveryWaitWatchdog(
    private val scheduler: CancellationWatchdogScheduler,
    private val timeoutMillis: Long,
    private val onFailure: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private var armed = false
    private var registration: CancellationWatchdogRegistration? = null

    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }

    fun arm(onTimeout: () -> Unit): RecoveryWaitWatchdogArmResult {
        synchronized(lock) {
            if (armed) return RecoveryWaitWatchdogArmResult.AlreadyArmed
            armed = true
        }
        val scheduled =
            try {
                scheduler.schedule(timeoutMillis) {
                    val shouldFire =
                        synchronized(lock) {
                            if (!armed) {
                                false
                            } else {
                                armed = false
                                registration = null
                                true
                            }
                        }
                    if (shouldFire) onTimeout()
                }
            } catch (error: Throwable) {
                synchronized(lock) {
                    armed = false
                    registration = null
                }
                return RecoveryWaitWatchdogArmResult.Rejected(error)
            }
        val cancelImmediately =
            synchronized(lock) {
                if (!armed) {
                    true
                } else {
                    registration = scheduled
                    false
                }
            }
        if (cancelImmediately) cancelRegistration(scheduled)
        return RecoveryWaitWatchdogArmResult.Armed
    }

    fun cancel() {
        val scheduled =
            synchronized(lock) {
                if (!armed) return
                armed = false
                registration.also { registration = null }
            }
        scheduled?.let(::cancelRegistration)
    }

    private fun cancelRegistration(scheduled: CancellationWatchdogRegistration) {
        try {
            scheduled.cancel()
        } catch (error: Throwable) {
            runCatching { onFailure("recovery wait watchdog cancellation", error) }
        }
    }
}

internal enum class ReconciliationLaunchFailure {
    SETUP,
    WATCHDOG,
    REGISTRATION,
    MAIN_DISPATCH,
    RECONCILIATION,
    OWNERSHIP,
}

internal enum class ReconciliationLaunchDisposition {
    LAUNCH,
    IGNORE_STALE,
    FAIL_CURRENT,
}

internal const val RECONCILIATION_LAUNCH_OWNERSHIP_FAILURE_MESSAGE =
    "Reconciled launch no longer owns a valid reservation"

internal sealed interface ServiceMainDispatchResult {
    data object Accepted : ServiceMainDispatchResult

    data class Rejected(
        val error: Throwable,
    ) : ServiceMainDispatchResult
}

internal data class ServiceReconciliationLaunchActions(
    val startForeground: () -> Unit,
    val acquireWakeLock: () -> Unit,
    val armRecoveryWaitWatchdog: ((() -> Unit) -> RecoveryWaitWatchdogArmResult),
    val detachReconciliationCompletion: () -> Boolean,
    val cancelRecoveryWaitWatchdog: () -> Unit,
    val registerReconciliationCompletion: ((Throwable?) -> Unit) -> Unit,
    val postToServiceMain: (() -> Unit) -> ServiceMainDispatchResult,
    val revalidateLaunch: () -> ReconciliationLaunchDisposition,
    val launchEngine: () -> Unit,
    val finishFailure: (ReconciliationLaunchFailure, Throwable) -> Unit,
    val finishNoEngineFailure: (ReconciliationLaunchFailure, Throwable) -> Unit,
    val onRecoveryWaitTimeout: () -> Unit,
    val onFailure: (String, Throwable) -> Unit,
)

/**
 * Pure foreground-before-wait launch coordinator.
 *
 * Exactly one reconciliation continuation is registered. Completion and the bounded watchdog are
 * always dispatched to the service main queue, race through one resolution claim, and revalidate
 * launch ownership before invoking any lifecycle action.
 */
internal class ServiceReconciliationLaunchCoordinator(
    private val actions: ServiceReconciliationLaunchActions,
) {
    private val lock = Any()
    private var started = false
    private var resolved = false

    fun start(): Boolean {
        synchronized(lock) {
            if (started) return false
            started = true
        }

        try {
            actions.startForeground()
            actions.acquireWakeLock()
        } catch (error: Throwable) {
            resolveFailure(ReconciliationLaunchFailure.SETUP, error)
            return true
        }

        val armResult =
            try {
                actions.armRecoveryWaitWatchdog {
                    detachReconciliationCompletionBestEffort()
                    dispatchToServiceMain("recovery wait timeout", ::resolveTimeout)
                }
            } catch (error: Throwable) {
                RecoveryWaitWatchdogArmResult.Rejected(error)
            }
        if (armResult is RecoveryWaitWatchdogArmResult.Rejected) {
            resolveFailure(ReconciliationLaunchFailure.WATCHDOG, armResult.error)
            return true
        }

        try {
            actions.registerReconciliationCompletion { error ->
                // CompletionStage turns a thrown callback into a failed dependent stage. There is
                // intentionally no owner for that dependent stage, so this callback must contain
                // both thrown and explicit dispatch rejection itself.
                try {
                    dispatchToServiceMain("reconciliation completion") { resolveCompletion(error) }
                } catch (callbackError: Throwable) {
                    reportFailure("reconciliation completion callback", callbackError)
                    resolveNoEngineDispatchFailure(callbackError)
                }
            }
        } catch (error: Throwable) {
            resolveFailure(ReconciliationLaunchFailure.REGISTRATION, error)
        }
        return true
    }

    private fun resolveCompletion(error: Throwable?) {
        resolveCurrentLaunch {
            if (error == null) {
                actions.launchEngine()
            } else {
                actions.finishFailure(ReconciliationLaunchFailure.RECONCILIATION, error)
            }
        }
    }

    private fun resolveTimeout() {
        resolveCurrentLaunch(actions.onRecoveryWaitTimeout)
    }

    private fun resolveFailure(
        reason: ReconciliationLaunchFailure,
        error: Throwable,
    ) {
        resolveCurrentLaunch { actions.finishFailure(reason, error) }
    }

    private fun dispatchToServiceMain(
        operation: String,
        action: () -> Unit,
    ) {
        val result =
            try {
                actions.postToServiceMain(action)
            } catch (error: Throwable) {
                ServiceMainDispatchResult.Rejected(error)
            }
        if (result is ServiceMainDispatchResult.Rejected) {
            reportFailure("$operation main dispatch", result.error)
            resolveNoEngineDispatchFailure(result.error)
        }
    }

    private fun resolveNoEngineDispatchFailure(error: Throwable) {
        if (!claimResolution()) return
        cancelRecoveryWaitWatchdogBestEffort()
        val disposition =
            try {
                actions.revalidateLaunch()
            } catch (revalidationError: Throwable) {
                reportFailure("reconciliation dispatch fallback revalidation", revalidationError)
                runNoEngineFailure(ReconciliationLaunchFailure.MAIN_DISPATCH, error)
                return
            }
        when (disposition) {
            ReconciliationLaunchDisposition.LAUNCH ->
                runNoEngineFailure(ReconciliationLaunchFailure.MAIN_DISPATCH, error)
            ReconciliationLaunchDisposition.IGNORE_STALE -> Unit
            ReconciliationLaunchDisposition.FAIL_CURRENT ->
                runNoEngineFailure(
                    ReconciliationLaunchFailure.OWNERSHIP,
                    IllegalStateException(RECONCILIATION_LAUNCH_OWNERSHIP_FAILURE_MESSAGE),
                )
        }
    }

    private fun runNoEngineFailure(
        reason: ReconciliationLaunchFailure,
        error: Throwable,
    ) {
        try {
            actions.finishNoEngineFailure(reason, error)
        } catch (fallbackError: Throwable) {
            reportFailure("reconciliation no-engine terminal fallback", fallbackError)
        }
    }

    private fun resolveCurrentLaunch(onLaunch: () -> Unit) {
        if (!claimResolution()) return
        cancelRecoveryWaitWatchdogBestEffort()
        when (actions.revalidateLaunch()) {
            ReconciliationLaunchDisposition.LAUNCH -> onLaunch()
            ReconciliationLaunchDisposition.IGNORE_STALE -> Unit
            ReconciliationLaunchDisposition.FAIL_CURRENT ->
                actions.finishFailure(
                    ReconciliationLaunchFailure.OWNERSHIP,
                    IllegalStateException(RECONCILIATION_LAUNCH_OWNERSHIP_FAILURE_MESSAGE),
                )
        }
    }

    private fun cancelRecoveryWaitWatchdogBestEffort() {
        try {
            actions.cancelRecoveryWaitWatchdog()
        } catch (error: Throwable) {
            reportFailure("recovery wait watchdog cancellation", error)
        }
    }

    private fun reportFailure(
        operation: String,
        error: Throwable,
    ) {
        runCatching { actions.onFailure(operation, error) }
    }

    private fun claimResolution(): Boolean {
        val claimed =
            synchronized(lock) {
                if (resolved) {
                    false
                } else {
                    resolved = true
                    true
                }
            }
        if (claimed) detachReconciliationCompletionBestEffort()
        return claimed
    }

    private fun detachReconciliationCompletionBestEffort() {
        try {
            actions.detachReconciliationCompletion()
        } catch (error: Throwable) {
            reportFailure("reconciliation completion detachment", error)
        }
    }
}

internal fun reconciliationLaunchDisposition(
    serviceDestroyed: Boolean,
    expectedContext: ActiveTaskContext,
    activeContext: ActiveTaskContext?,
    expectedGeneration: Long,
    installedGeneration: Long,
    reservation: TaskRuntimeSnapshot?,
): ReconciliationLaunchDisposition {
    if (serviceDestroyed || activeContext !== expectedContext) {
        return ReconciliationLaunchDisposition.IGNORE_STALE
    }
    if (
        expectedContext.launchGeneration != expectedGeneration ||
        installedGeneration != expectedGeneration
    ) {
        return ReconciliationLaunchDisposition.IGNORE_STALE
    }
    if (
        expectedContext.terminalOwnership != null ||
        expectedContext.lifecycle == ActiveTaskLifecycle.FINISHING ||
        expectedContext.lifecycle == ActiveTaskLifecycle.RELEASED ||
        expectedContext.isCancellationRequested
    ) {
        return ReconciliationLaunchDisposition.IGNORE_STALE
    }
    if (expectedContext.lifecycle != ActiveTaskLifecycle.AWAITING_ENGINE) {
        return ReconciliationLaunchDisposition.FAIL_CURRENT
    }
    val currentReservation = reservation ?: return ReconciliationLaunchDisposition.FAIL_CURRENT
    if (
        currentReservation.taskId != expectedContext.serviceTaskId ||
        currentReservation.taskKind != expectedContext.taskKind ||
        currentReservation.isTerminal
    ) {
        return ReconciliationLaunchDisposition.FAIL_CURRENT
    }
    return ReconciliationLaunchDisposition.LAUNCH
}

internal data class ServiceTerminalDirective(
    val outcome: ActiveTaskTerminalOutcome,
    val source: ActiveTaskFinishSource,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

internal enum class ServiceTerminationResult {
    IGNORED,
    AWAITING_ENGINE_TERMINAL,
    TERMINAL,
}

internal data class ServiceTaskCleanupActions(
    val cancelRecoveryWaitWatchdog: () -> Unit,
    val cancelUserWatchdog: () -> Unit,
    val removeRegistryObserver: () -> Unit,
    val releaseWakeLock: () -> Unit,
    val removeForeground: () -> Unit,
    val removeForegroundForNoEngineFallback: () -> Unit,
    val publishTerminalNotification: (ServiceTerminalDirective) -> Unit,
    val publishTerminalNotificationForNoEngineFallback: (ServiceTerminalDirective) -> Unit,
    val disposeTranscodeEngine: () -> Unit,
    val disposeAudioExtractionEngine: () -> Unit,
)

/**
 * Exactly-once owner for service-local resources. Every operation is isolated so one failure does
 * not strand the remaining resources. [releaseForServiceDestroy] is the idempotent lifecycle
 * fallback for the same release owned by a terminal winner.
 */
internal class ServiceTaskCleanup(
    private val actions: ServiceTaskCleanupActions,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var released = false

    fun releaseForTerminalWinner(
        includeServiceSurface: Boolean,
        terminal: ServiceTerminalDirective,
    ): Boolean = release(includeServiceSurface, terminal)

    fun releaseForServiceDestroy(terminal: ServiceTerminalDirective): Boolean =
        release(includeServiceSurface = true, terminal = terminal)

    fun releaseForNoEngineFallback(
        includeServiceSurface: Boolean,
        terminal: ServiceTerminalDirective,
    ): Boolean = release(includeServiceSurface, terminal, noEngineFallback = true)

    private fun release(
        includeServiceSurface: Boolean,
        terminal: ServiceTerminalDirective,
        noEngineFallback: Boolean = false,
    ): Boolean {
        synchronized(lock) {
            if (released) return false
            released = true
        }
        bestEffort("recovery wait watchdog", actions.cancelRecoveryWaitWatchdog)
        bestEffort("user cancellation watchdog", actions.cancelUserWatchdog)
        if (includeServiceSurface) {
            bestEffort("registry observer", actions.removeRegistryObserver)
        }
        bestEffort("wake lock", actions.releaseWakeLock)
        if (includeServiceSurface) {
            bestEffort("foreground") {
                if (noEngineFallback) {
                    actions.removeForegroundForNoEngineFallback()
                } else {
                    actions.removeForeground()
                }
            }
        }
        // The registry is a mirror, not a gate. The winner always attempts its terminal
        // notification, including when this launch no longer owns the foreground surface.
        bestEffort("terminal notification") {
            if (noEngineFallback) {
                actions.publishTerminalNotificationForNoEngineFallback(terminal)
            } else {
                actions.publishTerminalNotification(terminal)
            }
        }
        if (!noEngineFallback) {
            bestEffort("transcode engine", actions.disposeTranscodeEngine)
            bestEffort("audio extraction engine", actions.disposeAudioExtractionEngine)
        }
        return true
    }

    private fun bestEffort(
        description: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Throwable) {
            runCatching { onFailure(description, error) }
        }
    }
}

internal data class ServiceTerminationPolicyActions(
    val markPublicationDiscarding: (EngineTaskRoute) -> Unit,
    val cancelEngine: (EngineTaskRoute) -> Unit,
    val publishRegistryTerminal: (ServiceTerminalDirective) -> Unit,
    val scheduleTerminalWinner: ((() -> Unit) -> Unit),
    val cancelRecoveryWaitWatchdog: () -> Unit,
    val cancelUserWatchdog: () -> Unit,
    val removeRegistryObserver: () -> Unit,
    val releaseWakeLock: () -> Unit,
    val removeForeground: () -> Unit,
    val removeForegroundForNoEngineFallback: () -> Unit,
    val publishTerminalNotification: (ServiceTerminalDirective) -> Unit,
    val publishTerminalNotificationForNoEngineFallback: (ServiceTerminalDirective) -> Unit,
    val disposeTranscodeEngine: () -> Unit,
    val disposeAudioExtractionEngine: () -> Unit,
    val ownsServiceSurface: () -> Boolean,
    val clearActiveLaunch: () -> Unit,
    val stopService: () -> Unit,
    val onFailure: (String, Throwable) -> Unit = { _, _ -> },
)

/**
 * Pure orchestration policy used by every service termination entry point.
 *
 * It owns branch ordering, the terminal arbiter, exactly-once cleanup, and the stop attempt. Android
 * APIs and engines are supplied as actions, which keeps all reachable branches deterministic in JVM
 * tests while ensuring production invokes the same policy.
 */
internal class ServiceTerminationPolicy(
    private val context: ActiveTaskContext,
    private val cancellationWatchdog: UserCancellationWatchdog,
    private val actions: ServiceTerminationPolicyActions,
) {
    private val lock = Any()
    private var userCancellationStarted = false
    private val cleanup =
        ServiceTaskCleanup(
            actions =
                ServiceTaskCleanupActions(
                    cancelRecoveryWaitWatchdog = actions.cancelRecoveryWaitWatchdog,
                    cancelUserWatchdog = actions.cancelUserWatchdog,
                    removeRegistryObserver = actions.removeRegistryObserver,
                    releaseWakeLock = actions.releaseWakeLock,
                    removeForeground = actions.removeForeground,
                    removeForegroundForNoEngineFallback = actions.removeForegroundForNoEngineFallback,
                    publishTerminalNotification = actions.publishTerminalNotification,
                    publishTerminalNotificationForNoEngineFallback =
                        actions.publishTerminalNotificationForNoEngineFallback,
                    disposeTranscodeEngine = actions.disposeTranscodeEngine,
                    disposeAudioExtractionEngine = actions.disposeAudioExtractionEngine,
                ),
            onFailure = actions.onFailure,
        )

    fun handleCancel(taskId: String?): ServiceTerminationResult {
        if (taskId.isNullOrBlank() || taskId != context.serviceTaskId) {
            return currentResult(ServiceTerminationResult.IGNORED)
        }
        return when (
            val decision =
                context.requestCancellation(
                    taskId = taskId,
                    generation = context.launchGeneration,
                    source = ActiveTaskCancellationSource.USER,
                )
        ) {
            ActiveTaskCancellationDecision.Ignored -> currentResult(ServiceTerminationResult.IGNORED)
            ActiveTaskCancellationDecision.FinishBeforeEngine -> {
                finishCancellationBeforeEngine()
                ServiceTerminationResult.TERMINAL
            }
            is ActiveTaskCancellationDecision.CancelEngine -> handleAssignedUserCancellation(decision.route)
        }
    }

    fun onTimeout(): ServiceTerminationResult =
        terminateImmediately(
            cancellationSource = ActiveTaskCancellationSource.TIMEOUT,
            terminal = timeoutTerminal(),
        )

    fun onRecoveryWaitTimeout(): ServiceTerminationResult =
        terminateImmediately(
            cancellationSource = ActiveTaskCancellationSource.TIMEOUT,
            terminal = recoveryWaitTimeoutTerminal(),
        )

    fun onDestroy(): ServiceTerminationResult {
        val requested =
            terminateImmediately(
                cancellationSource = ActiveTaskCancellationSource.SERVICE_DESTROY,
                terminal = destroyTerminal(),
            )
        val fallback = context.terminalOwnership?.toDirective() ?: destroyTerminal()
        releaseForServiceDestroy(fallback)
        return currentResult(requested)
    }

    fun finishCancellationBeforeEngine(): ActiveTaskFinishDecision {
        val terminal =
            when (context.cancellationSource) {
                ActiveTaskCancellationSource.TIMEOUT -> timeoutTerminal()
                ActiveTaskCancellationSource.SERVICE_DESTROY -> destroyTerminal()
                else ->
                    ServiceTerminalDirective(
                        outcome = ActiveTaskTerminalOutcome.CANCELLED,
                        source = ActiveTaskFinishSource.CANCEL_BEFORE_ENGINE,
                    )
            }
        return finish(terminal)
    }

    fun finish(
        terminal: ServiceTerminalDirective,
        publishRegistryTerminal: (() -> Unit)? = null,
    ): ActiveTaskFinishDecision =
        context.finishOnce(
            generation = context.launchGeneration,
            outcome = terminal.outcome,
            source = terminal.source,
            onWinner = actions.scheduleTerminalWinner,
            publishTerminal = {
                bestEffort("registry terminal publication") {
                    publishRegistryTerminal?.invoke() ?: actions.publishRegistryTerminal(terminal)
                }
            },
            releaseResources = {
                releaseForTerminalWinner(terminal)
            },
        )

    /**
     * Terminal fallback for a launch whose reconciliation continuation could not reach service
     * main. The coordinator calls this only while no engine route exists. It completes directly on
     * the callback thread and deliberately omits every engine/Media3 action.
     */
    fun finishNoEngineFailure(terminal: ServiceTerminalDirective): ActiveTaskFinishDecision {
        if (context.engineRoute != null) {
            reportFailure(
                "reconciliation no-engine fallback invariant",
                IllegalStateException("No-engine fallback received an assigned engine route"),
            )
        }
        return context.finishOnce(
            generation = context.launchGeneration,
            outcome = terminal.outcome,
            source = terminal.source,
            publishTerminal = {
                bestEffort("registry terminal publication") {
                    actions.publishRegistryTerminal(terminal)
                }
            },
            releaseResources = {
                releaseForNoEngineFallback(terminal)
            },
        )
    }

    fun cancelLateStartedEngine(route: EngineTaskRoute) {
        bestEffort("publication discard boundary") { actions.markPublicationDiscarding(route) }
        bestEffort("engine cancellation") { actions.cancelEngine(route) }
    }

    private fun handleAssignedUserCancellation(route: EngineTaskRoute): ServiceTerminationResult {
        val shouldStart =
            synchronized(lock) {
                if (userCancellationStarted) {
                    false
                } else {
                    userCancellationStarted = true
                    true
                }
            }
        if (!shouldStart) return currentResult(ServiceTerminationResult.AWAITING_ENGINE_TERMINAL)

        val timeoutTerminal = userCancellationTimeoutTerminal()
        val armResult =
            cancellationWatchdog.arm {
                finish(timeoutTerminal)
            }
        if (armResult is UserCancellationWatchdogArmResult.Rejected) {
            reportFailure("user cancellation watchdog arm", armResult.error)
        }

        // Cancellation is still attempted after scheduler rejection. A synchronous engine terminal
        // may win finishOnce; the immediate fail-closed fallback below then harmlessly loses.
        bestEffort("publication discard boundary") { actions.markPublicationDiscarding(route) }
        bestEffort("engine cancellation") { actions.cancelEngine(route) }

        if (armResult is UserCancellationWatchdogArmResult.Rejected) {
            finish(timeoutTerminal)
            return ServiceTerminationResult.TERMINAL
        }
        return currentResult(ServiceTerminationResult.AWAITING_ENGINE_TERMINAL)
    }

    private fun terminateImmediately(
        cancellationSource: ActiveTaskCancellationSource,
        terminal: ServiceTerminalDirective,
    ): ServiceTerminationResult =
        when (
            val decision =
                context.requestCancellation(
                    taskId = context.serviceTaskId,
                    generation = context.launchGeneration,
                    source = cancellationSource,
                )
        ) {
            ActiveTaskCancellationDecision.Ignored -> currentResult(ServiceTerminationResult.IGNORED)
            ActiveTaskCancellationDecision.FinishBeforeEngine -> {
                finish(terminal)
                ServiceTerminationResult.TERMINAL
            }
            is ActiveTaskCancellationDecision.CancelEngine -> {
                bestEffort("publication discard boundary") {
                    actions.markPublicationDiscarding(decision.route)
                }
                bestEffort("engine cancellation") { actions.cancelEngine(decision.route) }
                finish(terminal)
                ServiceTerminationResult.TERMINAL
            }
        }

    private fun releaseForTerminalWinner(terminal: ServiceTerminalDirective) {
        val ownsSurface = ownsServiceSurfaceFailClosed()
        val released = cleanup.releaseForTerminalWinner(ownsSurface, terminal)
        if (!released || !ownsSurface) return
        bestEffort("active launch clear", actions.clearActiveLaunch)
        if (terminal.source != ActiveTaskFinishSource.ON_DESTROY) {
            bestEffort("service stop", actions.stopService)
        }
    }

    private fun releaseForServiceDestroy(terminal: ServiceTerminalDirective) {
        if (!cleanup.releaseForServiceDestroy(terminal)) return
        bestEffort("active launch clear", actions.clearActiveLaunch)
    }

    private fun releaseForNoEngineFallback(terminal: ServiceTerminalDirective) {
        val ownsSurface = ownsServiceSurfaceFailClosed()
        val released = cleanup.releaseForNoEngineFallback(ownsSurface, terminal)
        if (!released || !ownsSurface) return
        bestEffort("active launch clear", actions.clearActiveLaunch)
        bestEffort("service stop", actions.stopService)
    }

    private fun ownsServiceSurfaceFailClosed(): Boolean =
        try {
            actions.ownsServiceSurface()
        } catch (error: Throwable) {
            reportFailure("service surface ownership", error)
            true
        }

    private fun currentResult(default: ServiceTerminationResult): ServiceTerminationResult =
        if (context.terminalOwnership != null) ServiceTerminationResult.TERMINAL else default

    private fun bestEffort(
        operation: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Throwable) {
            reportFailure(operation, error)
        }
    }

    private fun reportFailure(
        operation: String,
        error: Throwable,
    ) {
        runCatching { actions.onFailure(operation, error) }
    }

    private fun timeoutTerminal(): ServiceTerminalDirective =
        ServiceTerminalDirective(
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.SERVICE_TIMEOUT,
            errorCode = EngineErrorCode.UNKNOWN.wireName,
            errorMessage = "系统已结束超时的媒体处理任务",
        )

    private fun recoveryWaitTimeoutTerminal(): ServiceTerminalDirective =
        ServiceTerminalDirective(
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.SERVICE_TIMEOUT,
            errorCode = EngineErrorCode.UNKNOWN.wireName,
            errorMessage = "启动恢复检查超时，请重试",
        )

    private fun destroyTerminal(): ServiceTerminalDirective =
        ServiceTerminalDirective(
            outcome = ActiveTaskTerminalOutcome.FAILED,
            source = ActiveTaskFinishSource.ON_DESTROY,
            errorCode = EngineErrorCode.UNKNOWN.wireName,
            errorMessage = "媒体处理服务已意外终止",
        )

    private fun userCancellationTimeoutTerminal(): ServiceTerminalDirective =
        ServiceTerminalDirective(
            outcome = ActiveTaskTerminalOutcome.CANCELLED,
            source = ActiveTaskFinishSource.USER_CANCEL_TIMEOUT,
        )

    private fun ActiveTaskTerminalOwnership.toDirective(): ServiceTerminalDirective =
        ServiceTerminalDirective(
            outcome = outcome,
            source = source,
            errorCode = if (outcome == ActiveTaskTerminalOutcome.FAILED) EngineErrorCode.UNKNOWN.wireName else null,
            errorMessage =
                when (source) {
                    ActiveTaskFinishSource.SERVICE_TIMEOUT -> "系统已结束超时的媒体处理任务"
                    ActiveTaskFinishSource.ON_DESTROY -> "媒体处理服务已意外终止"
                    else -> if (outcome == ActiveTaskTerminalOutcome.FAILED) "媒体处理任务未能完成" else null
                },
        )
}
