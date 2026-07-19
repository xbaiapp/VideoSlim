package com.videoslim.videoslim

internal data class TaskRuntimeSnapshot(
    val taskId: String,
    val percent: Double,
    val state: String,
    val sourceUri: String,
    val outputFileName: String,
    val startedAtEpochMs: Long,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun toProgressMap(): Map<String, Any?> =
        linkedMapOf(
            "taskId" to taskId,
            "percent" to percent,
            "state" to state,
            "outputUri" to outputUri,
            "outputFileName" to outputFileName,
            "errorCode" to errorCode,
            "errorMessage" to errorMessage,
        )

    fun toSnapshotMap(): Map<String, Any?> =
        LinkedHashMap<String, Any?>(toProgressMap()).apply {
            this["sourceUri"] = sourceUri
            this["outputFileName"] = outputFileName
            this["startedAtEpochMs"] = startedAtEpochMs
        }

    val isTerminal: Boolean
        get() = state != STATE_RUNNING

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_SUCCESS = "success"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
    }
}

/**
 * Process-local, thread-safe task registry shared by the foreground service and Flutter channels.
 *
 * It intentionally persists no active state. Android process death ends the task; the durable recovery
 * journal owns orphan reconciliation on the next legal startup.
 */
internal class ProcessingRegistry {
    private val lock = Any()
    private val observers = LinkedHashSet<(TaskRuntimeSnapshot) -> Unit>()
    private var current: TaskRuntimeSnapshot? = null

    fun reserve(
        taskId: String,
        sourceUri: String,
        outputFileName: String,
        startedAtEpochMs: Long,
    ): TaskRuntimeSnapshot {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank" }
        require(outputFileName.isNotBlank()) { "outputFileName must not be blank" }
        require(startedAtEpochMs >= 0L) { "startedAtEpochMs must not be negative" }
        val snapshot: TaskRuntimeSnapshot
        val listeners: List<(TaskRuntimeSnapshot) -> Unit>
        synchronized(lock) {
            check(current?.isTerminal != false) { "A processing task is already running" }
            snapshot =
                TaskRuntimeSnapshot(
                    taskId = taskId,
                    percent = 0.0,
                    state = TaskRuntimeSnapshot.STATE_RUNNING,
                    sourceUri = sourceUri,
                    outputFileName = outputFileName,
                    startedAtEpochMs = startedAtEpochMs,
                )
            current = snapshot
            listeners = observers.toList()
        }
        notifyObservers(listeners, snapshot)
        return snapshot
    }

    fun apply(
        taskId: String,
        percent: Double,
        state: String,
        outputUri: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Boolean {
        require(percent.isFinite() && percent in 0.0..100.0) { "percent must be finite and in range" }
        require(state in ALLOWED_STATES) { "Unknown task state: $state" }
        val updated: TaskRuntimeSnapshot
        val listeners: List<(TaskRuntimeSnapshot) -> Unit>
        synchronized(lock) {
            val previous = current ?: return false
            if (previous.taskId != taskId || previous.isTerminal) return false
            validateTerminalFields(state, outputUri, errorCode, errorMessage)
            val monotonicPercent =
                if (state == TaskRuntimeSnapshot.STATE_SUCCESS) {
                    100.0
                } else {
                    maxOf(previous.percent, percent)
                }
            updated =
                previous.copy(
                    percent = monotonicPercent,
                    state = state,
                    outputUri = outputUri,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            if (updated == previous) return true
            current = updated
            listeners = observers.toList()
        }
        notifyObservers(listeners, updated)
        return true
    }

    fun snapshot(): TaskRuntimeSnapshot? = synchronized(lock) { current }

    fun updateOutputFileName(
        taskId: String,
        outputFileName: String,
    ): Boolean {
        require(outputFileName.isNotBlank() && '/' !in outputFileName && '\\' !in outputFileName) {
            "outputFileName must be a safe leaf name"
        }
        val updated: TaskRuntimeSnapshot
        val listeners: List<(TaskRuntimeSnapshot) -> Unit>
        synchronized(lock) {
            val previous = current ?: return false
            if (previous.taskId != taskId || previous.isTerminal) return false
            if (previous.outputFileName == outputFileName) return true
            updated = previous.copy(outputFileName = outputFileName)
            current = updated
            listeners = observers.toList()
        }
        notifyObservers(listeners, updated)
        return true
    }

    fun addObserver(observer: (TaskRuntimeSnapshot) -> Unit) {
        val snapshot: TaskRuntimeSnapshot?
        synchronized(lock) {
            observers += observer
            snapshot = current
        }
        snapshot?.let { notifyObserver(observer, it) }
    }

    fun removeObserver(observer: (TaskRuntimeSnapshot) -> Unit) {
        synchronized(lock) { observers -= observer }
    }

    fun releaseLaunchFailure(taskId: String): Boolean =
        synchronized(lock) {
            val snapshot = current
            if (snapshot?.taskId != taskId || snapshot.isTerminal) return@synchronized false
            current = null
            true
        }

    private fun validateTerminalFields(
        state: String,
        outputUri: String?,
        errorCode: String?,
        errorMessage: String?,
    ) {
        when (state) {
            TaskRuntimeSnapshot.STATE_RUNNING ->
                require(outputUri == null && errorCode == null && errorMessage == null) {
                    "running must not contain output or error fields"
                }

            TaskRuntimeSnapshot.STATE_CANCELLED ->
                require(
                    outputUri == null &&
                        (
                            (errorCode == null && errorMessage == null) ||
                                (errorCode == EngineErrorCode.CANCELLED.wireName &&
                                    !errorMessage.isNullOrBlank())
                        ),
                ) {
                    "cancelled accepts no output and only an optional CANCELLED error pair"
                }

            TaskRuntimeSnapshot.STATE_SUCCESS ->
                require(!outputUri.isNullOrBlank() && errorCode == null && errorMessage == null) {
                    "success requires only outputUri"
                }

            TaskRuntimeSnapshot.STATE_FAILED ->
                require(outputUri == null && !errorCode.isNullOrBlank() && !errorMessage.isNullOrBlank()) {
                    "failed requires errorCode and errorMessage"
                }
        }
    }

    private fun notifyObservers(
        listeners: List<(TaskRuntimeSnapshot) -> Unit>,
        snapshot: TaskRuntimeSnapshot,
    ) {
        listeners.forEach { notifyObserver(it, snapshot) }
    }

    private fun notifyObserver(
        observer: (TaskRuntimeSnapshot) -> Unit,
        snapshot: TaskRuntimeSnapshot,
    ) {
        runCatching { observer(snapshot) }
    }

    private companion object {
        val ALLOWED_STATES =
            setOf(
                TaskRuntimeSnapshot.STATE_RUNNING,
                TaskRuntimeSnapshot.STATE_SUCCESS,
                TaskRuntimeSnapshot.STATE_FAILED,
                TaskRuntimeSnapshot.STATE_CANCELLED,
            )
    }
}
