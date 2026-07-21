package com.videoslim.videoslim

internal data class PublicationLaunchIdentity(
    val serviceTaskId: String,
    val launchGeneration: Long,
    val engineTaskId: String,
)

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

/** One-shot bounded deadline for accepted user cancellation. */
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

    fun arm(onTimeout: () -> Unit): Boolean {
        synchronized(lock) {
            if (armed) return false
            armed = true
        }
        val scheduled =
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
        synchronized(lock) {
            if (!armed) {
                scheduled.cancel()
            } else {
                registration = scheduled
            }
        }
        return true
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

internal data class ServiceTaskCleanupActions(
    val cancelUserWatchdog: () -> Unit,
    val removeRegistryObserver: () -> Unit,
    val releaseWakeLock: () -> Unit,
    val removeForeground: () -> Unit,
    val publishTerminalNotification: () -> Unit,
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

    fun releaseForTerminalWinner(includeServiceSurface: Boolean): Boolean =
        release(includeServiceSurface)

    fun releaseForServiceDestroy(): Boolean = release(includeServiceSurface = true)

    private fun release(includeServiceSurface: Boolean): Boolean {
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
            bestEffort("terminal notification", actions.publishTerminalNotification)
        }
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
