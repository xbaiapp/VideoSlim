package com.videoslim.videoslim

internal enum class ActiveTaskLifecycle {
    AWAITING_ENGINE,
    ENGINE_ASSIGNED,
    FINISHING,
    RELEASED,
}

internal data class EngineTaskRoute(
    val taskKind: TaskKind,
    val engineTaskId: String,
)

internal enum class ActiveTaskCancellationSource {
    USER,
    TIMEOUT,
    SERVICE_DESTROY,
}

internal sealed interface ActiveTaskCancellationDecision {
    data object Ignored : ActiveTaskCancellationDecision

    data object FinishBeforeEngine : ActiveTaskCancellationDecision

    data class CancelEngine(
        val route: EngineTaskRoute,
    ) : ActiveTaskCancellationDecision
}

internal enum class ActiveTaskTerminalOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

internal enum class ActiveTaskFinishSource {
    ENGINE_TERMINAL,
    START_EXCEPTION,
    CANCEL_BEFORE_ENGINE,
    USER_CANCEL_TIMEOUT,
    SERVICE_TIMEOUT,
    ON_DESTROY,
}

internal data class ActiveTaskTerminalOwnership(
    val outcome: ActiveTaskTerminalOutcome,
    val source: ActiveTaskFinishSource,
)

internal sealed interface ActiveTaskFinishDecision {
    data object StaleGeneration : ActiveTaskFinishDecision

    data class Won(
        val ownership: ActiveTaskTerminalOwnership,
    ) : ActiveTaskFinishDecision

    data class Lost(
        val ownership: ActiveTaskTerminalOwnership,
    ) : ActiveTaskFinishDecision
}

/**
 * Owns the complete identity and lifecycle of one service launch.
 *
 * The processing registry is only a user-visible mirror. Engine routing, cancellation and terminal
 * ownership must be decided here so registry loss or replacement cannot leak service resources or
 * route cancellation to the wrong engine.
 */
internal class ActiveTaskContext(
    val serviceTaskId: String,
    val taskKind: TaskKind,
    val launchGeneration: Long,
) {
    private val lock = Any()
    private var currentLifecycle = ActiveTaskLifecycle.AWAITING_ENGINE
    private var currentEngineRoute: EngineTaskRoute? = null
    private var cancellationRequested = false
    private var currentCancellationSource: ActiveTaskCancellationSource? = null
    private var currentTerminalOwnership: ActiveTaskTerminalOwnership? = null
    private var winnerCompletionStarted = false

    init {
        require(serviceTaskId.isNotBlank()) { "serviceTaskId must not be blank" }
        require(launchGeneration >= 0L) { "launchGeneration must not be negative" }
    }

    val lifecycle: ActiveTaskLifecycle
        get() = synchronized(lock) { currentLifecycle }

    val engineTaskId: String?
        get() = synchronized(lock) { currentEngineRoute?.engineTaskId }

    val engineRoute: EngineTaskRoute?
        get() = synchronized(lock) { currentEngineRoute }

    val isCancellationRequested: Boolean
        get() = synchronized(lock) { cancellationRequested }

    val cancellationSource: ActiveTaskCancellationSource?
        get() = synchronized(lock) { currentCancellationSource }

    val terminalOwnership: ActiveTaskTerminalOwnership?
        get() = synchronized(lock) { currentTerminalOwnership }

    fun owns(
        taskId: String,
        generation: Long,
    ): Boolean = serviceTaskId == taskId && launchGeneration == generation

    fun canLaunch(generation: Long): Boolean =
        synchronized(lock) {
            generation == launchGeneration &&
                currentLifecycle == ActiveTaskLifecycle.AWAITING_ENGINE &&
                !cancellationRequested &&
                currentTerminalOwnership == null
        }

    fun assignEngineTaskId(
        generation: Long,
        engineTaskId: String,
    ): EngineTaskRoute? {
        require(engineTaskId.isNotBlank()) { "engineTaskId must not be blank" }
        synchronized(lock) {
            if (
                generation != launchGeneration ||
                cancellationRequested ||
                currentTerminalOwnership != null ||
                currentLifecycle == ActiveTaskLifecycle.RELEASED
            ) {
                return null
            }
            currentEngineRoute?.let { assigned ->
                return assigned.takeIf { it.engineTaskId == engineTaskId }
            }
            return EngineTaskRoute(taskKind, engineTaskId).also { route ->
                currentEngineRoute = route
                currentLifecycle = ActiveTaskLifecycle.ENGINE_ASSIGNED
            }
        }
    }

    fun routeForStartedEngine(
        generation: Long,
        engineTaskId: String,
    ): EngineTaskRoute? {
        require(engineTaskId.isNotBlank()) { "engineTaskId must not be blank" }
        return EngineTaskRoute(taskKind, engineTaskId).takeIf { generation == launchGeneration }
    }

    fun acceptsEngineEvent(
        generation: Long,
        engineTaskId: String,
    ): Boolean =
        synchronized(lock) {
            if (
                generation != launchGeneration ||
                engineTaskId.isBlank() ||
                currentTerminalOwnership != null ||
                currentLifecycle == ActiveTaskLifecycle.RELEASED
            ) {
                return@synchronized false
            }
            currentEngineRoute?.engineTaskId == engineTaskId
        }

    fun requestCancellation(
        taskId: String,
        generation: Long,
        source: ActiveTaskCancellationSource,
    ): ActiveTaskCancellationDecision =
        synchronized(lock) {
            if (
                !owns(taskId, generation) ||
                currentTerminalOwnership != null ||
                currentLifecycle == ActiveTaskLifecycle.RELEASED
            ) {
                return@synchronized ActiveTaskCancellationDecision.Ignored
            }
            cancellationRequested = true
            if (currentCancellationSource == null || source == ActiveTaskCancellationSource.TIMEOUT) {
                currentCancellationSource = source
            }
            currentEngineRoute?.let(ActiveTaskCancellationDecision::CancelEngine)
                ?: ActiveTaskCancellationDecision.FinishBeforeEngine
        }

    /**
     * Atomically claims terminal ownership, then gives the winner an idempotent completion without
     * holding [lock]. The engine route remains installed in [ActiveTaskLifecycle.FINISHING] until
     * completion publishes the terminal state and releases resources. If scheduling winner work
     * throws, completion runs immediately so the launch cannot be stranded.
     */
    fun finishOnce(
        generation: Long,
        outcome: ActiveTaskTerminalOutcome,
        source: ActiveTaskFinishSource,
        onWinner: ((() -> Unit) -> Unit) = { completeWinner -> completeWinner() },
        publishTerminal: () -> Unit = {},
        releaseResources: () -> Unit = {},
    ): ActiveTaskFinishDecision {
        val ownership = ActiveTaskTerminalOwnership(outcome, source)
        synchronized(lock) {
            if (generation != launchGeneration) return ActiveTaskFinishDecision.StaleGeneration
            currentTerminalOwnership?.let { return ActiveTaskFinishDecision.Lost(it) }
            currentTerminalOwnership = ownership
            currentLifecycle = ActiveTaskLifecycle.FINISHING
        }

        val completeWinner: () -> Unit = completeWinner@{
            val shouldComplete =
                synchronized(lock) {
                    if (winnerCompletionStarted) {
                        false
                    } else {
                        winnerCompletionStarted = true
                        true
                    }
                }
            if (!shouldComplete) return@completeWinner

            try {
                publishTerminal()
            } finally {
                try {
                    releaseResources()
                } finally {
                    synchronized(lock) {
                        currentEngineRoute = null
                        currentLifecycle = ActiveTaskLifecycle.RELEASED
                    }
                }
            }
        }

        try {
            onWinner(completeWinner)
        } catch (schedulingError: Throwable) {
            try {
                completeWinner()
            } catch (completionError: Throwable) {
                if (completionError !== schedulingError) {
                    runCatching { schedulingError.addSuppressed(completionError) }
                }
            }
            throw schedulingError
        }
        return ActiveTaskFinishDecision.Won(ownership)
    }
}
