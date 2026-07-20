package com.videoslim.videoslim

import kotlin.math.max

/**
 * Keeps at most one scheduled delivery while retaining the newest monotonic progress value.
 * The scheduler may be an Android Handler, but the policy stays JVM-testable.
 */
internal class CoalescedProgressDispatcher(
    private val schedule: (Runnable) -> Unit,
    private val deliver: (Double) -> Unit,
) {
    private val lock = Any()
    private var latest = 0.0
    private var updateVersion = 0L
    private var scheduled = false

    fun update(value: Double) {
        val shouldSchedule =
            synchronized(lock) {
                latest = max(latest, value)
                updateVersion += 1L
                if (scheduled) {
                    false
                } else {
                    scheduled = true
                    true
                }
            }
        if (shouldSchedule) schedule(Runnable(::drain))
    }

    private fun drain() {
        val value: Double
        val deliveredVersion: Long
        synchronized(lock) {
            value = latest
            deliveredVersion = updateVersion
        }
        deliver(value)
        val shouldReschedule =
            synchronized(lock) {
                if (updateVersion == deliveredVersion) {
                    scheduled = false
                    false
                } else {
                    true
                }
            }
        if (shouldReschedule) schedule(Runnable(::drain))
    }
}
