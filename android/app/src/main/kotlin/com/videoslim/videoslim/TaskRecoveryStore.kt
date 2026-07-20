package com.videoslim.videoslim

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

enum class RecoveryStage {
    PREPARING,
    TRANSFORMING,
    ALLOCATED,
    PUBLISHING,
    PUBLISHED,
    DISCARDING,
}

data class TaskRecoveryRecord(
    val taskId: String,
    val stage: RecoveryStage,
    val tempFileName: String,
    val expectedOutputDisplayName: String,
    val actualOutputDisplayName: String?,
    val mediaStoreUri: String?,
    val legacyOutputPath: String?,
    val startedAtEpochMs: Long,
)

sealed interface TaskRecoveryDecodeResult {
    data class Success(val record: TaskRecoveryRecord) : TaskRecoveryDecodeResult

    data class Invalid(val reason: String) : TaskRecoveryDecodeResult
}

/** Pure, strict codec for the single in-flight recovery record. */
internal object TaskRecoveryCodec {
    private const val VERSION = 1
    private const val NULL_VALUE = "~"
    private val expectedKeys =
        setOf(
            "version",
            "taskId",
            "stage",
            "tempFileName",
            "expectedOutputDisplayName",
            "actualOutputDisplayName",
            "mediaStoreUri",
            "legacyOutputPath",
            "startedAtEpochMs",
        )
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(record: TaskRecoveryRecord): String =
        buildString {
            appendLine("version=$VERSION")
            appendLine("taskId=${encodeRequired(record.taskId)}")
            appendLine("stage=${record.stage.name}")
            appendLine("tempFileName=${encodeRequired(record.tempFileName)}")
            appendLine("expectedOutputDisplayName=${encodeRequired(record.expectedOutputDisplayName)}")
            appendLine("actualOutputDisplayName=${encodeNullable(record.actualOutputDisplayName)}")
            appendLine("mediaStoreUri=${encodeNullable(record.mediaStoreUri)}")
            appendLine("legacyOutputPath=${encodeNullable(record.legacyOutputPath)}")
            append("startedAtEpochMs=${record.startedAtEpochMs}")
        }

    fun decode(raw: String): TaskRecoveryDecodeResult {
        if (raw.isBlank()) return invalid("record is blank")
        return try {
            val values = linkedMapOf<String, String>()
            raw.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return invalid("malformed field")
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                if (key !in expectedKeys || values.put(key, value) != null) {
                    return invalid("unknown or duplicate field")
                }
            }
            if (values.keys != expectedKeys) return invalid("missing field")
            val version = values.getValue("version").toIntOrNull()
            if (version != VERSION) return invalid("unsupported version")
            val stage =
                runCatching { RecoveryStage.valueOf(values.getValue("stage")) }.getOrNull()
                    ?: return invalid("unknown stage")
            val record =
                TaskRecoveryRecord(
                    taskId = decodeRequired(values.getValue("taskId")),
                    stage = stage,
                    tempFileName = decodeRequired(values.getValue("tempFileName")),
                    expectedOutputDisplayName =
                        decodeRequired(values.getValue("expectedOutputDisplayName")),
                    actualOutputDisplayName =
                        decodeNullable(values.getValue("actualOutputDisplayName")),
                    mediaStoreUri = decodeNullable(values.getValue("mediaStoreUri")),
                    legacyOutputPath = decodeNullable(values.getValue("legacyOutputPath")),
                    startedAtEpochMs =
                        values.getValue("startedAtEpochMs").toLongOrNull()
                            ?: return invalid("invalid start time"),
                )
            validateRecoveryRecord(record)?.let(::invalid)
                ?: TaskRecoveryDecodeResult.Success(record)
        } catch (_: IllegalArgumentException) {
            invalid("invalid encoded value")
        } catch (_: Throwable) {
            invalid("unreadable record")
        }
    }

    private fun encodeRequired(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun encodeNullable(value: String?): String = value?.let(::encodeRequired) ?: NULL_VALUE

    private fun decodeRequired(value: String): String {
        if (value == NULL_VALUE) throw IllegalArgumentException("required value is null")
        return String(decoder.decode(value), StandardCharsets.UTF_8)
    }

    private fun decodeNullable(value: String): String? =
        if (value == NULL_VALUE) null else decodeRequired(value)

    private fun invalid(reason: String) = TaskRecoveryDecodeResult.Invalid(reason)
}

internal fun isValidRecoveryTempFileName(name: String): Boolean =
    RECOVERY_TEMP_NAME.matches(name) &&
        '/' !in name &&
        '\\' !in name &&
        name != "." &&
        name != ".."

internal fun isAllowedRecoveryTransition(
    current: RecoveryStage,
    next: RecoveryStage,
): Boolean =
    current == next ||
        when (current) {
            RecoveryStage.PREPARING -> next == RecoveryStage.TRANSFORMING
            RecoveryStage.TRANSFORMING ->
                next == RecoveryStage.ALLOCATED || next == RecoveryStage.PUBLISHING
            RecoveryStage.ALLOCATED -> next == RecoveryStage.PUBLISHING
            RecoveryStage.PUBLISHING ->
                next == RecoveryStage.PUBLISHED || next == RecoveryStage.DISCARDING
            RecoveryStage.PUBLISHED -> next == RecoveryStage.DISCARDING
            RecoveryStage.DISCARDING -> false
        }

private fun validateRecoveryRecord(record: TaskRecoveryRecord): String? {
    if (!isSafeJournalText(record.taskId, MAX_TASK_ID_LENGTH)) return "invalid task id"
    if (!isValidRecoveryTempFileName(record.tempFileName)) return "invalid temp filename"
    if (!isSafeRecoveryOutputName(record.expectedOutputDisplayName)) return "invalid expected output name"
    if (
        record.actualOutputDisplayName != null &&
        !isSafeRecoveryOutputName(record.actualOutputDisplayName)
    ) {
        return "invalid actual output name"
    }
    if (record.startedAtEpochMs <= 0L) return "invalid start time"
    if (record.mediaStoreUri != null && !isSafeJournalText(record.mediaStoreUri, MAX_URI_LENGTH)) {
        return "invalid media URI"
    }
    if (record.legacyOutputPath != null) {
        if (!isSafeJournalText(record.legacyOutputPath, MAX_PATH_LENGTH)) return "invalid legacy path"
        val path = File(record.legacyOutputPath)
        if (!path.isAbsolute || path.absolutePath != path.canonicalPath) return "non-canonical legacy path"
        if (path.name != record.actualOutputDisplayName) return "legacy path does not match output name"
    }
    when (record.stage) {
        RecoveryStage.PREPARING,
        RecoveryStage.TRANSFORMING,
        -> {
            if (
                record.actualOutputDisplayName != null ||
                record.mediaStoreUri != null ||
                record.legacyOutputPath != null
            ) {
                return "publication target exists before publishing"
            }
        }
        RecoveryStage.ALLOCATED -> {
            val allocationUri = record.mediaStoreUri
            if (
                record.actualOutputDisplayName != null ||
                allocationUri == null ||
                record.legacyOutputPath != null ||
                (
                    !OrphanCleanupPolicy.isAppMediaVideoUri(allocationUri) &&
                        !OrphanCleanupPolicy.isSafDocumentUri(allocationUri)
                )
            ) {
                return "allocated record has an invalid unverified publication target"
            }
        }
        RecoveryStage.PUBLISHING,
        RecoveryStage.PUBLISHED,
        RecoveryStage.DISCARDING,
        -> {
            if (record.actualOutputDisplayName == null || record.mediaStoreUri == null) {
                return "publishing record has no complete target"
            }
        }
    }
    return null
}

private fun isSafeRecoveryOutputName(name: String): Boolean =
    isSafeJournalText(name, MAX_OUTPUT_NAME_LENGTH) &&
        name.endsWith(".mp4", ignoreCase = true) &&
        name.substringBeforeLast('.', missingDelimiterValue = "").isNotBlank() &&
        '/' !in name &&
        '\\' !in name &&
        name != "." &&
        name != ".."

private fun isSafeJournalText(value: String, maxLength: Int): Boolean =
    value.isNotBlank() &&
        value.length <= maxLength &&
        value.none { it.code < 0x20 || it.code == 0x7f }

/**
 * Synchronous, single-task transaction journal. Mutation methods throw when a commit fails so a
 * caller never starts or reports a task whose recovery boundary was not durably recorded.
 */
class TaskRecoveryStore(
    context: Context,
    private val logger: (String) -> Unit = {},
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val quarantineDirectory =
        File(context.applicationContext.filesDir, QUARANTINE_DIRECTORY_NAME)

    @Throws(IOException::class)
    fun begin(
        taskId: String,
        tempFileName: String,
        expectedOutputDisplayName: String,
        startedAtEpochMs: Long = System.currentTimeMillis(),
    ): TaskRecoveryRecord = synchronized(TRANSACTION_LOCK) {
        if (preferences.contains(RECORD_KEY)) {
            log("recovery begin refused because a previous record still exists")
            throw IllegalStateException("An uncleared task recovery record already exists")
        }
        val record =
            TaskRecoveryRecord(
                taskId = taskId,
                stage = RecoveryStage.PREPARING,
                tempFileName = tempFileName,
                expectedOutputDisplayName = expectedOutputDisplayName,
                actualOutputDisplayName = null,
                mediaStoreUri = null,
                legacyOutputPath = null,
                startedAtEpochMs = startedAtEpochMs,
            )
        validateRecoveryRecord(record)?.let { throw IllegalArgumentException(it) }
        persist(record, "begin")
        record
    }

    @Throws(IOException::class)
    fun updateStage(taskId: String, stage: RecoveryStage): TaskRecoveryRecord = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        if (!isAllowedRecoveryTransition(current.stage, stage)) {
            throw IllegalStateException("Invalid recovery stage transition ${current.stage} -> $stage")
        }
        if (current.stage == stage) return@synchronized current
        if (
            stage == RecoveryStage.ALLOCATED ||
            stage == RecoveryStage.PUBLISHING ||
            stage == RecoveryStage.PUBLISHED
        ) {
            throw IllegalStateException("Publication stages require their transaction-specific boundary")
        }
        current.copy(stage = stage).also { persist(it, "stage") }
    }

    @Throws(IOException::class)
    fun recordPublicationTarget(
        taskId: String,
        actualOutputDisplayName: String,
        mediaStoreUri: String,
        canonicalLegacyOutputPath: String? = null,
    ): TaskRecoveryRecord = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        if (current.stage == RecoveryStage.PUBLISHING) {
            if (
                current.actualOutputDisplayName == actualOutputDisplayName &&
                current.mediaStoreUri == mediaStoreUri &&
                current.legacyOutputPath == canonicalLegacyOutputPath
            ) {
                return@synchronized current
            }
            throw IllegalStateException("A different publication target is already recorded")
        }
        if (
            current.stage != RecoveryStage.TRANSFORMING &&
            current.stage != RecoveryStage.ALLOCATED
        ) {
            throw IllegalStateException("Publication target cannot be recorded in ${current.stage}")
        }
        if (
            current.stage == RecoveryStage.ALLOCATED &&
            (current.mediaStoreUri != mediaStoreUri || canonicalLegacyOutputPath != null)
        ) {
            throw IllegalStateException("Verified publication target does not match its allocation")
        }
        val updated =
            current.copy(
                stage = RecoveryStage.PUBLISHING,
                actualOutputDisplayName = actualOutputDisplayName,
                mediaStoreUri = mediaStoreUri,
                legacyOutputPath = canonicalLegacyOutputPath,
            )
        validateRecoveryRecord(updated)?.let { throw IllegalArgumentException(it) }
        persist(updated, "publication-target")
        updated
    }

    @Throws(IOException::class)
    fun recordPublicationAllocation(
        taskId: String,
        publicationUri: String,
    ): TaskRecoveryRecord = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        if (current.stage == RecoveryStage.ALLOCATED) {
            if (current.mediaStoreUri == publicationUri) return@synchronized current
            throw IllegalStateException("A different publication allocation is already recorded")
        }
        if (current.stage != RecoveryStage.TRANSFORMING) {
            throw IllegalStateException("Publication allocation cannot be recorded in ${current.stage}")
        }
        val updated =
            current.copy(
                stage = RecoveryStage.ALLOCATED,
                mediaStoreUri = publicationUri,
            )
        validateRecoveryRecord(updated)?.let { throw IllegalArgumentException(it) }
        persist(updated, "publication-allocation")
        updated
    }

    @Throws(IOException::class)
    fun markPublished(taskId: String): TaskRecoveryRecord = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        if (current.stage == RecoveryStage.PUBLISHED) return@synchronized current
        if (
            current.stage != RecoveryStage.PUBLISHING ||
            current.actualOutputDisplayName == null ||
            (current.mediaStoreUri == null && current.legacyOutputPath == null)
        ) {
            throw IllegalStateException("A publication target must be recorded before completion")
        }
        current.copy(stage = RecoveryStage.PUBLISHED).also { persist(it, "published") }
    }

    @Throws(IOException::class)
    fun markDiscarding(taskId: String): TaskRecoveryRecord = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        if (current.stage == RecoveryStage.DISCARDING) return@synchronized current
        if (!isAllowedRecoveryTransition(current.stage, RecoveryStage.DISCARDING)) {
            throw IllegalStateException("Publication cannot be discarded from ${current.stage}")
        }
        current.copy(stage = RecoveryStage.DISCARDING).also { persist(it, "discarding") }
    }

    fun load(): TaskRecoveryRecord? = synchronized(TRANSACTION_LOCK) {
        val raw =
            try {
                preferences.getString(RECORD_KEY, null)
            } catch (error: Throwable) {
                log("recovery record could not be read: ${error.javaClass.simpleName}")
                return@synchronized null
            } ?: return@synchronized null
        when (val result = TaskRecoveryCodec.decode(raw)) {
            is TaskRecoveryDecodeResult.Success -> result.record
            is TaskRecoveryDecodeResult.Invalid -> {
                log("recovery record ignored: ${result.reason}")
                null
            }
        }
    }

    /** Drops only an unreadable journal value; public media is deliberately left untouched. */
    @Throws(IOException::class)
    fun discardInvalidRecord(): Boolean = synchronized(TRANSACTION_LOCK) {
        if (!preferences.contains(RECORD_KEY)) return@synchronized false
        val reason =
            try {
                val raw = preferences.getString(RECORD_KEY, null)
                    ?: return@synchronized false
                when (val result = TaskRecoveryCodec.decode(raw)) {
                    is TaskRecoveryDecodeResult.Success -> return@synchronized false
                    is TaskRecoveryDecodeResult.Invalid -> result.reason
                }
            } catch (error: Throwable) {
                "unreadable ${error.javaClass.simpleName}"
            }
        commit(preferences.edit().remove(RECORD_KEY), "invalid-clear")
        log("invalid recovery record cleared: $reason")
        true
    }

    @Throws(IOException::class)
    fun clear(taskId: String) = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        commit(preferences.edit().remove(RECORD_KEY), "clear")
        log("recovery record cleared task=${current.taskId}")
    }

    /**
     * Moves an ownership-unprovable record out of the single active slot. The
     * exact journal remains durable without permanently blocking new tasks.
     */
    @Throws(IOException::class)
    fun quarantine(
        taskId: String,
        reason: String,
    ): File = synchronized(TRANSACTION_LOCK) {
        val current = loadForMutation(taskId)
        if (!quarantineDirectory.exists() && !quarantineDirectory.mkdirs()) {
            throw IOException("Unable to create recovery quarantine directory")
        }
        val quarantineParent =
            quarantineDirectory.parentFile
                ?: throw IOException("Recovery quarantine directory has no parent")
        val stableName =
            UUID.nameUUIDFromBytes(taskId.toByteArray(StandardCharsets.UTF_8)).toString() +
                QUARANTINE_EXTENSION
        val destination = File(quarantineDirectory, stableName)
        val encoded = TaskRecoveryCodec.encode(current)
        val temporary = File.createTempFile("recovery-", ".tmp", quarantineDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            commitQuarantineBoundary(
                commitDestination = {
                    if (destination.exists()) {
                        val existing =
                            runCatching {
                                destination.readText(StandardCharsets.UTF_8)
                            }.getOrNull()
                        if (existing != encoded) {
                            throw IOException(
                                "Conflicting recovery quarantine record already exists",
                            )
                        }
                    } else if (!temporary.renameTo(destination)) {
                        throw IOException("Unable to commit recovery quarantine record")
                    }
                    if (!destination.isFile) {
                        throw IOException("Committed recovery quarantine record is unavailable")
                    }
                },
                syncQuarantineDirectory = { syncDirectoryDurably(quarantineDirectory) },
                syncParentDirectory = { syncDirectoryDurably(quarantineParent) },
                clearActiveJournal = {
                    commit(preferences.edit().remove(RECORD_KEY), "quarantine-clear")
                },
            )
            log("recovery record quarantined task=${current.taskId} reason=$reason")
            destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun requireCurrent(): TaskRecoveryRecord =
        load() ?: throw IllegalStateException("No valid task recovery record exists")

    private fun loadForMutation(taskId: String): TaskRecoveryRecord {
        val record = requireCurrent()
        if (record.taskId != taskId) {
            throw IllegalStateException("Task recovery record belongs to a different task")
        }
        return record
    }

    private fun persist(record: TaskRecoveryRecord, boundary: String) {
        check(validateRecoveryRecord(record) == null) { "Refusing to persist an invalid recovery record" }
        commit(
            preferences.edit().putString(RECORD_KEY, TaskRecoveryCodec.encode(record)),
            boundary,
        )
    }

    private fun commit(editor: SharedPreferences.Editor, boundary: String) {
        val committed =
            try {
                editor.commit()
            } catch (error: Throwable) {
                log("recovery $boundary commit threw ${error.javaClass.simpleName}")
                throw IOException("Unable to commit task recovery $boundary boundary", error)
            }
        if (!committed) {
            log("recovery $boundary commit returned false")
            throw IOException("Unable to commit task recovery $boundary boundary")
        }
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        const val PREFERENCES_NAME = "videoslim_task_recovery"
        const val RECORD_KEY = "active_task_v1"
        const val QUARANTINE_DIRECTORY_NAME = "videoslim-recovery-quarantine"
        const val QUARANTINE_EXTENSION = ".journal"
        val TRANSACTION_LOCK = Any()
    }
}

internal fun commitQuarantineBoundary(
    commitDestination: () -> Unit,
    syncQuarantineDirectory: () -> Unit,
    syncParentDirectory: () -> Unit,
    clearActiveJournal: () -> Unit,
) {
    commitDestination()
    syncQuarantineDirectory()
    syncParentDirectory()
    clearActiveJournal()
}

@Throws(IOException::class)
private fun syncDirectoryDurably(directory: File) {
    val descriptor =
        try {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
        } catch (error: Throwable) {
            throw IOException("Unable to open recovery quarantine directory for sync", error)
        }
    try {
        Os.fsync(descriptor)
    } catch (error: Throwable) {
        throw IOException("Unable to sync recovery quarantine directory", error)
    } finally {
        runCatching { Os.close(descriptor) }
    }
}

private val RECOVERY_TEMP_NAME =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.mp4$")
private const val MAX_TASK_ID_LENGTH = 128
private const val MAX_OUTPUT_NAME_LENGTH = 255
private const val MAX_URI_LENGTH = 2048
private const val MAX_PATH_LENGTH = 4096
