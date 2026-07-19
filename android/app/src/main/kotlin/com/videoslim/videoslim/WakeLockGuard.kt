package com.videoslim.videoslim

import android.content.Context
import android.os.PowerManager

internal interface WakeLockHandle {
    val isHeld: Boolean

    fun acquire(timeoutMs: Long)

    fun release()
}

internal class AndroidPartialWakeLock(context: Context) : WakeLockHandle {
    private val wakeLock: PowerManager.WakeLock =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }

    override val isHeld: Boolean
        get() = wakeLock.isHeld

    override fun acquire(timeoutMs: Long) {
        wakeLock.acquire(timeoutMs)
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private companion object {
        const val WAKE_LOCK_TAG = "VideoSlim:processing"
    }
}

/** Owns one bounded partial WakeLock and makes terminal cleanup idempotent. */
internal class WakeLockGuard(
    private val handle: WakeLockHandle,
) {
    private val lock = Any()
    private var ownerTaskId: String? = null

    fun acquire(
        taskId: String,
        timeoutMs: Long,
    ) {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(timeoutMs > 0L) { "WakeLock timeout must be positive" }
        synchronized(lock) {
            check(ownerTaskId == null) { "WakeLock is already owned by another task" }
            handle.acquire(timeoutMs)
            ownerTaskId = taskId
        }
    }

    fun release(taskId: String): Boolean =
        synchronized(lock) {
            if (ownerTaskId != taskId) return@synchronized false
            ownerTaskId = null
            runCatching { handle.release() }
            true
        }

    fun releaseAll() {
        synchronized(lock) {
            if (ownerTaskId == null && !handle.isHeld) return
            ownerTaskId = null
            runCatching { handle.release() }
        }
    }
}
