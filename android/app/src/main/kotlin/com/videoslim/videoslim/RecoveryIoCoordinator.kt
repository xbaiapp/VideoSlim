package com.videoslim.videoslim

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
