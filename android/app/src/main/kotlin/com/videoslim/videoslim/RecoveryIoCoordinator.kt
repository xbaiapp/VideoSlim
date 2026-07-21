package com.videoslim.videoslim

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer

/**
 * A one-shot observation whose retained completion payload can be detached from a shared stage.
 *
 * [CompletionStage] retains only [CompletionHolder]. The holder atomically hands the potentially
 * heavy action to either stage completion or [detach], so a timed-out launch does not remain
 * reachable from a process-scoped reconciliation gate.
 */
internal class DetachableReconciliationObservation {
    private val observed = AtomicBoolean(false)
    private val holder = CompletionHolder()

    val attachedActionCount: Int
        get() = holder.attachedActionCount

    fun <T> observe(
        stage: CompletionStage<T>,
        action: (Throwable?) -> Unit,
    ) {
        check(observed.compareAndSet(false, true)) { "Reconciliation completion was already observed" }
        check(holder.attach(action)) { "Reconciliation completion action was already attached" }
        stage.whenComplete(holder)
    }

    fun detach(): Boolean = holder.takeAction() != null

    private class CompletionHolder : BiConsumer<Any?, Throwable?> {
        private val detached = AtomicBoolean(false)
        private val action = AtomicReference<((Throwable?) -> Unit)?>(null)

        val attachedActionCount: Int
            get() = if (action.get() == null) 0 else 1

        fun attach(candidate: (Throwable?) -> Unit): Boolean {
            if (!action.compareAndSet(null, candidate)) return false
            if (detached.get()) action.getAndSet(null)
            return true
        }

        fun takeAction(): ((Throwable?) -> Unit)? {
            detached.set(true)
            return action.getAndSet(null)
        }

        override fun accept(
            ignored: Any?,
            error: Throwable?,
        ) {
            takeAction()?.invoke(error)
        }
    }
}

/**
 * Process-scoped one-shot executor for recovery reconciliation.
 *
 * [completion] is observational only: reconciliation starts exclusively through [startOnce]. The
 * shared stage is completed for action failures and submission rejection, so no listener can be
 * stranded. No caller waits for the worker.
 */
internal class ProcessReconciliationGate(
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, RECONCILIATION_THREAD_NAME)
        },
) {
    private val started = AtomicBoolean(false)
    private val sharedCompletion = CompletableFuture<Unit>()
    private val observationalCompletion: CompletionStage<Unit> =
        ObservationalCompletionStage(sharedCompletion)

    fun startOnce(action: () -> Unit): CompletionStage<Unit> {
        if (!started.compareAndSet(false, true)) return observationalCompletion

        var submitted = false
        try {
            executor.execute {
                try {
                    action()
                    sharedCompletion.complete(Unit)
                } catch (error: Throwable) {
                    sharedCompletion.completeExceptionally(error)
                } finally {
                    executor.shutdown()
                }
            }
            submitted = true
        } catch (error: Throwable) {
            sharedCompletion.completeExceptionally(error)
        } finally {
            if (!submitted) executor.shutdown()
        }
        return observationalCompletion
    }

    fun completion(): CompletionStage<Unit> = observationalCompletion

    private companion object {
        const val RECONCILIATION_THREAD_NAME = "videoslim-reconciliation"
    }
}
