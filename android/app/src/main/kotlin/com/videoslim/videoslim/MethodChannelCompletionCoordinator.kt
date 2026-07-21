package com.videoslim.videoslim

/**
 * Pure exactly-once coordinator for MethodChannel replies that may race with disposal.
 *
 * A claimed reply is replaced by the disposal outcome until its main-thread action is delivered.
 * Delivered replies remain final. All callbacks are dispatched outside the coordinator lock.
 */
internal class MethodChannelCompletionCoordinator(
    private val dispatch: (() -> Unit) -> Unit,
) {
    private val lock = Any()
    private val pending = mutableSetOf<Completion>()
    private var disposed = false

    fun register(onDisposed: () -> Unit): Completion? {
        val completion =
            synchronized(lock) {
                if (disposed) {
                    null
                } else {
                    Completion(onDisposed).also(pending::add)
                }
            }
        if (completion == null) dispatch(onDisposed)
        return completion
    }

    fun dispose() {
        val toClose =
            synchronized(lock) {
                if (disposed) return
                disposed = true
                pending.toList()
            }
        toClose.forEach(Completion::scheduleDelivery)
    }

    inner class Completion internal constructor(
        private val onDisposed: () -> Unit,
    ) {
        private var claimed = false
        private var delivered = false
        private var delivery: (() -> Unit)? = null

        fun complete(action: () -> Unit): Boolean {
            val accepted =
                synchronized(lock) {
                    if (disposed || claimed || delivered) {
                        false
                    } else {
                        claimed = true
                        delivery = action
                        true
                    }
                }
            if (accepted) scheduleDelivery()
            return accepted
        }

        fun submit(
            submission: () -> Unit,
            onSynchronousFailure: (Throwable) -> Unit,
        ) {
            try {
                submission()
            } catch (error: Throwable) {
                complete { onSynchronousFailure(error) }
            }
        }

        internal fun scheduleDelivery() {
            dispatch(::deliver)
        }

        private fun deliver() {
            val action =
                synchronized(lock) {
                    if (delivered || (!claimed && !disposed)) return
                    delivered = true
                    pending.remove(this)
                    if (disposed) onDisposed else delivery
                }
            action?.invoke()
        }
    }
}
