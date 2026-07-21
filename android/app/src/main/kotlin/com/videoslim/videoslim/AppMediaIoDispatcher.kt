package com.videoslim.videoslim

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Every operation here may cross a provider, resolver, or persistent-preference boundary. */
internal enum class MediaIoOperation {
    VIDEO_METADATA,
    AUDIO_METADATA,
    OUTPUT_DESTINATION_VALIDATION,
    VIDEO_GRANT_PERSISTENCE,
    OUTPUT_FOLDER_REPLACEMENT,
    OUTPUT_LOCATION_READ,
    OUTPUT_LOCATION_RESET,
    MEDIA_OPEN_PREPARATION,
    MEDIA_SHARE_PREPARATION,
    MEDIA_DELETE_PREFLIGHT,
    MEDIA_DELETE_RETRY,
}

internal class AppMediaIoRejectedException(
    val operation: MediaIoOperation,
) : RejectedExecutionException("Media I/O dispatcher rejected $operation")

/**
 * Process-owned bounded FIFO for blocking media/control I/O.
 *
 * Activity-scoped channels borrow this dispatcher. They never own or stop its worker.
 */
internal class AppMediaIoDispatcher(
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    threadFactory: ThreadFactory = MediaIoThreadFactory(),
) {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity.also { require(it > 0) }),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun <T> submit(
        operation: MediaIoOperation,
        block: () -> T,
        completion: (Result<T>) -> Unit,
    ) {
        val callbackDelivered = AtomicBoolean(false)
        fun deliver(outcome: Result<T>) {
            if (callbackDelivered.compareAndSet(false, true)) completion(outcome)
        }

        try {
            executor.execute {
                deliver(runCatching(block))
            }
        } catch (_: RejectedExecutionException) {
            deliver(Result.failure(AppMediaIoRejectedException(operation)))
        }
    }

    /** Requests a graceful drain without waiting on the caller (normally the platform thread). */
    fun shutdown() {
        executor.shutdown()
    }

    internal fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = executor.awaitTermination(timeout, unit)

    internal companion object {
        const val DEFAULT_QUEUE_CAPACITY = 32
    }
}

private class MediaIoThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "videoslim-media-io-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
}
