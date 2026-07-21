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
    val cancelUserWatchdog: () -> Unit,
    val removeRegistryObserver: () -> Unit,
    val releaseWakeLock: () -> Unit,
    val removeForeground: () -> Unit,
    val publishTerminalNotification: (ServiceTerminalDirective) -> Unit,
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

    private fun release(
        includeServiceSurface: Boolean,
        terminal: ServiceTerminalDirective,
    ): Boolean {
        synchronized(lock) {
            if (released) return false
            released = true
        }
        bestEffort("user cancellation watchdog", actions.cancelUserWatchdog)
        if (includeServiceSurface) {
            bestEffort("registry observer", actions.removeRegistryObserver)
        }
        bestEffort("wake lock", actions.releaseWakeLock)
        if (includeServiceSurface) {
            bestEffort("foreground", actions.removeForeground)
        }
        // The registry is a mirror, not a gate. The winner always attempts its terminal
        // notification, including when this launch no longer owns the foreground surface.
        bestEffort("terminal notification") { actions.publishTerminalNotification(terminal) }
        bestEffort("transcode engine", actions.disposeTranscodeEngine)
        bestEffort("audio extraction engine", actions.disposeAudioExtractionEngine)
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
    val cancelUserWatchdog: () -> Unit,
    val removeRegistryObserver: () -> Unit,
    val releaseWakeLock: () -> Unit,
    val removeForeground: () -> Unit,
    val publishTerminalNotification: (ServiceTerminalDirective) -> Unit,
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
                    cancelUserWatchdog = actions.cancelUserWatchdog,
                    removeRegistryObserver = actions.removeRegistryObserver,
                    releaseWakeLock = actions.releaseWakeLock,
                    removeForeground = actions.removeForeground,
                    publishTerminalNotification = actions.publishTerminalNotification,
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
