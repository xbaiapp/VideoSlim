package com.videoslim.videoslim

import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class AppLogPriority {
    NORMAL,
    PRIORITY,
}

internal data class AppLogPendingSnapshot(
    val commandCount: Int,
    val byteCount: Int,
)

internal class AppLogQueueFullException : IllegalStateException("native log queue is full")

internal class AppLogDispatcherClosedException : IllegalStateException("native log dispatcher is closed")

/**
 * Process-wide bounded dispatcher and sole asynchronous owner of [AppLogStorage].
 *
 * Pending work is bounded by count and normalized UTF-8 bytes. Progress entries
 * coalesce by task key; protected native/control entries can displace progress
 * and normal entries, but accepted protected entries are never evicted.
 */
internal class AppLogDispatcher(
    private val storage: AppLogStorage,
    private val maxPendingCommands: Int = DEFAULT_MAX_PENDING_COMMANDS,
    private val maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
    private val maxEntryBytes: Int = AppLogStore.DEFAULT_MAX_ENTRY_BYTES,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "videoslim-log-writer").apply { isDaemon = true }
    },
    sessionId: String = UUID.randomUUID().toString().take(8),
    private val clock: () -> Instant = Instant::now,
    private val normalizer: (String, Int) -> AppLogNormalizationResult =
        AppLogEntryNormalizer::normalizePrefixBounded,
) {
    companion object {
        const val DEFAULT_MAX_PENDING_COMMANDS = 1024
        const val DEFAULT_MAX_PENDING_BYTES = 512 * 1024
        private const val MAX_PROGRESS_KEY_BYTES = 1024
    }

    private val lock = Any()
    private val queue = ArrayDeque<Command>()
    private val pendingProgress = mutableMapOf<String, AppendCommand>()
    private var sequence = 0L
    private val processSessionId =
        normalizer(
            sessionId.ifBlank { "process" },
            64,
        ).value
    private var pendingBytes = 0
    private var accepting = true
    private var drainScheduled = false
    private var shutdownFinalizing = false
    private var shutdownOutcome: Result<Unit>? = null
    private val shutdownCallbacks = mutableListOf<(Result<Unit>) -> Unit>()

    init {
        require(maxPendingCommands > 0) { "maxPendingCommands must be positive" }
        require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
    }

    fun append(
        entry: String,
        priority: AppLogPriority = AppLogPriority.NORMAL,
        completion: (Result<Unit>) -> Unit = {},
    ) {
        val normalized = normalizeForAdmission(entry)
        enqueue(
            AppendCommand(
                entry = normalized.value,
                byteCount = normalized.utf8ByteCount,
                classification =
                    if (priority == AppLogPriority.PRIORITY) {
                        Classification.PROTECTED
                    } else {
                        Classification.NORMAL
                    },
                progressKey = null,
                completion = completion,
            ),
        )
    }

    fun native(
        message: String,
        completion: (Result<Unit>) -> Unit,
    ) {
        enqueueNative(
            message = message,
            classification = Classification.PROTECTED,
            progressKey = null,
            completion = completion,
        )
    }

    fun progress(
        taskId: String,
        message: String,
    ) {
        val progressKey =
            normalizer(
                taskId.ifBlank { "unknown-task" },
                MAX_PROGRESS_KEY_BYTES,
            ).value
        enqueueNative(
            message = message,
            classification = Classification.PROGRESS,
            progressKey = progressKey,
            completion = {},
        )
    }

    fun readAll(completion: (Result<String>) -> Unit) {
        enqueue(ReadCommand(completion))
    }

    fun createShareSnapshot(completion: (Result<File>) -> Unit) {
        enqueue(SnapshotCommand(completion))
    }

    fun flush(completion: (Result<Unit>) -> Unit = {}) {
        enqueue(FlushCommand(completion))
    }

    /** Stops admission immediately, drains accepted work, then terminates the owned executor. */
    fun shutdown(completion: (Result<Unit>) -> Unit = {}) {
        var finalizeNow = false
        var completedOutcome: Result<Unit>? = null
        synchronized(lock) {
            completedOutcome = shutdownOutcome
            if (completedOutcome == null) {
                shutdownCallbacks += completion
                accepting = false
                pendingProgress.clear()
                if (!drainScheduled && !shutdownFinalizing) {
                    shutdownFinalizing = true
                    finalizeNow = true
                }
            }
        }
        completedOutcome?.let { safeComplete(completion, it) }
        if (finalizeNow) finalizeShutdown()
    }

    internal fun pendingSnapshot(): AppLogPendingSnapshot = synchronized(lock) {
        AppLogPendingSnapshot(queue.size, pendingBytes)
    }

    private fun normalizeForAdmission(entry: String): AppLogNormalizationResult =
        normalizer(
            entry,
            minOf(maxEntryBytes, maxPendingBytes),
        )

    private fun enqueueNative(
        message: String,
        classification: Classification,
        progressKey: String?,
        completion: (Result<Unit>) -> Unit,
    ) {
        // Potentially huge producer input is bounded before the admission lock.
        val boundedMessage = normalizeForAdmission(message).value
        enqueue(
            failure = { error -> completion(Result.failure(error)) },
            commandFactory = {
                // Sequence allocation and FIFO admission are one locked ordering point.
                val timestamp = clock()
                sequence += 1
                val eventId = "$processSessionId-$sequence"
                val normalized =
                    normalizeForAdmission(
                        "$timestamp [INFO] [native] [event:$eventId] $boundedMessage",
                    )
                AppendCommand(
                    entry = normalized.value,
                    byteCount = normalized.utf8ByteCount,
                    classification = classification,
                    progressKey = progressKey,
                    completion = completion,
                )
            },
        )
    }

    private fun enqueue(command: Command) {
        enqueue(
            failure = command::fail,
            commandFactory = { command },
        )
    }

    private fun enqueue(
        failure: (Throwable) -> Unit,
        commandFactory: () -> Command,
    ) {
        val deferred = mutableListOf<() -> Unit>()
        var scheduleDrain = false
        synchronized(lock) {
            if (!accepting) {
                val error = AppLogDispatcherClosedException()
                deferred += { failure(error) }
            } else {
                val command =
                    try {
                        commandFactory()
                    } catch (error: Throwable) {
                        deferred += { failure(error) }
                        null
                    }
                if (command != null) {
                    val evictions = selectEvictions(command)
                    if (evictions == null) {
                        val error = AppLogQueueFullException()
                        deferred += { command.fail(error) }
                    } else {
                        evictions.forEach { victim ->
                            removePending(victim)
                            if (victim.classification == Classification.NORMAL) {
                                val error = AppLogQueueFullException()
                                deferred += { victim.fail(error) }
                            }
                        }
                        queue.addLast(command)
                        pendingBytes += command.byteCount
                        if (command.isBarrier) {
                            // A later task update must not replace progress accepted before this barrier.
                            pendingProgress.clear()
                        } else if (command is AppendCommand && command.progressKey != null) {
                            pendingProgress[command.progressKey] = command
                        }
                        if (!drainScheduled) {
                            drainScheduled = true
                            scheduleDrain = true
                        }
                    }
                }
            }
        }
        deferred.forEach { callback -> runCatching(callback) }
        if (scheduleDrain) scheduleDrain()
    }

    private fun selectEvictions(incoming: Command): List<Command>? {
        var projectedCount = queue.size + 1
        var projectedBytes = pendingBytes + incoming.byteCount
        val selected = mutableListOf<Command>()

        if (incoming is AppendCommand && incoming.progressKey != null) {
            pendingProgress[incoming.progressKey]?.let { previous ->
                selected += previous
                projectedCount -= 1
                projectedBytes -= previous.byteCount
            }
        }

        while (projectedCount > maxPendingCommands || projectedBytes > maxPendingBytes) {
            val victim = selectPressureVictim(incoming.classification, selected) ?: return null
            selected += victim
            projectedCount -= 1
            projectedBytes -= victim.byteCount
        }
        return selected
    }

    private fun selectPressureVictim(
        incoming: Classification,
        alreadySelected: List<Command>,
    ): Command? {
        fun firstOf(classification: Classification): Command? =
            queue.firstOrNull { candidate ->
                candidate.classification == classification &&
                    alreadySelected.none { selected -> selected === candidate }
            }

        return when (incoming) {
            Classification.PROGRESS -> firstOf(Classification.PROGRESS)
            Classification.NORMAL -> firstOf(Classification.PROGRESS)
            Classification.PROTECTED ->
                firstOf(Classification.PROGRESS) ?: firstOf(Classification.NORMAL)
        }
    }

    private fun removePending(command: Command) {
        check(queue.remove(command)) { "pending log command disappeared" }
        pendingBytes -= command.byteCount
        if (command is AppendCommand && command.progressKey != null) {
            if (pendingProgress[command.progressKey] === command) {
                pendingProgress.remove(command.progressKey)
            }
        }
    }

    private fun scheduleDrain() {
        try {
            executor.execute(::drainLoop)
        } catch (error: Throwable) {
            handleScheduleFailure(error)
        }
    }

    private fun handleScheduleFailure(error: Throwable) {
        val stranded: List<Command>
        var finalizeNow = false
        synchronized(lock) {
            if (!drainScheduled) return
            drainScheduled = false
            accepting = false
            stranded = queue.toList()
            queue.clear()
            pendingProgress.clear()
            pendingBytes = 0
            if (!shutdownFinalizing) {
                shutdownFinalizing = true
                finalizeNow = true
            }
        }
        stranded.forEach { command -> runCatching { command.fail(error) } }
        if (finalizeNow) finalizeShutdown()
    }

    private fun drainLoop() {
        while (true) {
            var finalizeNow = false
            val command = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    drainScheduled = false
                    if (!accepting && !shutdownFinalizing) {
                        shutdownFinalizing = true
                        finalizeNow = true
                    }
                    null
                } else {
                    pendingBytes -= next.byteCount
                    if (next is AppendCommand && next.progressKey != null) {
                        if (pendingProgress[next.progressKey] === next) {
                            pendingProgress.remove(next.progressKey)
                        }
                    }
                    next
                }
            }
            if (command == null) {
                if (finalizeNow) finalizeShutdown()
                return
            }
            execute(command)
        }
    }

    private fun execute(command: Command) {
        when (command) {
            is AppendCommand -> {
                val outcome = runCatching { storage.append(command.entry) }
                safeComplete(command.completion, outcome)
            }
            is ReadCommand -> safeComplete(command.completion, runCatching { storage.readAll() })
            is SnapshotCommand ->
                safeComplete(command.completion, runCatching { storage.createShareSnapshot() })
            is FlushCommand -> safeComplete(command.completion, Result.success(Unit))
        }
    }

    private fun finalizeShutdown() {
        val outcome = runCatching { executor.shutdown() }
        val callbacks: List<(Result<Unit>) -> Unit>
        synchronized(lock) {
            shutdownOutcome = outcome
            callbacks = shutdownCallbacks.toList()
            shutdownCallbacks.clear()
        }
        callbacks.forEach { completion -> safeComplete(completion, outcome) }
    }

    private fun <T> safeComplete(
        completion: (Result<T>) -> Unit,
        outcome: Result<T>,
    ) {
        runCatching { completion(outcome) }
    }

    private enum class Classification {
        PROGRESS,
        NORMAL,
        PROTECTED,
    }

    private sealed class Command(
        val byteCount: Int,
        val classification: Classification,
        val isBarrier: Boolean,
    ) {
        abstract fun fail(error: Throwable)
    }

    private class AppendCommand(
        val entry: String,
        byteCount: Int,
        classification: Classification,
        val progressKey: String?,
        val completion: (Result<Unit>) -> Unit,
    ) : Command(byteCount, classification, isBarrier = false) {
        override fun fail(error: Throwable) {
            completion(Result.failure(error))
        }
    }

    private class ReadCommand(
        val completion: (Result<String>) -> Unit,
    ) : Command(byteCount = 0, classification = Classification.PROTECTED, isBarrier = true) {
        override fun fail(error: Throwable) {
            completion(Result.failure(error))
        }
    }

    private class SnapshotCommand(
        val completion: (Result<File>) -> Unit,
    ) : Command(byteCount = 0, classification = Classification.PROTECTED, isBarrier = true) {
        override fun fail(error: Throwable) {
            completion(Result.failure(error))
        }
    }

    private class FlushCommand(
        val completion: (Result<Unit>) -> Unit,
    ) : Command(byteCount = 0, classification = Classification.PROTECTED, isBarrier = true) {
        override fun fail(error: Throwable) {
            completion(Result.failure(error))
        }
    }
}
