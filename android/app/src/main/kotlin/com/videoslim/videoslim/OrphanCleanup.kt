package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

enum class CleanupAction {
    DELETE,
    KEEP,
    ALREADY_ABSENT,
    SKIP_UNSAFE,
}

data class ScopedMediaEntry(
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val isPending: Int?,
    val ownerPackageName: String?,
)

internal data class DocumentOutputEntry(
    val uri: String,
    val displayName: String?,
)

internal data class LegacyMediaEntry(
    val uri: String,
    val displayName: String?,
    val dataPath: String?,
)

/** Pure ownership policy shared by startup cleanup and local JVM tests. */
internal object OrphanCleanupPolicy {
    const val SCOPED_RELATIVE_PATH = "Movies/VideoSlim/"

    fun cacheAction(fileName: String, activeTempFileName: String?): CleanupAction =
        when {
            !isValidRecoveryTempFileName(fileName) -> CleanupAction.SKIP_UNSAFE
            fileName == activeTempFileName -> CleanupAction.KEEP
            else -> CleanupAction.DELETE
        }

    fun scopedAction(
        record: TaskRecoveryRecord,
        observed: ScopedMediaEntry?,
        expectedOwnerPackageName: String,
    ): CleanupAction {
        val uri = record.mediaStoreUri
        if (expectedOwnerPackageName.isBlank()) return CleanupAction.SKIP_UNSAFE
        if (record.stage == RecoveryStage.ALLOCATED) {
            if (
                uri == null ||
                record.actualOutputDisplayName != null ||
                record.legacyOutputPath != null ||
                !isAppMediaVideoUri(uri) ||
                !isValidRecoveryTempFileName(record.tempFileName)
            ) {
                return CleanupAction.SKIP_UNSAFE
            }
            if (observed == null) return CleanupAction.ALREADY_ABSENT
            return CleanupAction.SKIP_UNSAFE
        }
        val actualName = record.actualOutputDisplayName
        if (
            uri == null ||
            actualName == null ||
            record.legacyOutputPath != null ||
            !isAppMediaVideoUri(uri) ||
            !isValidRecoveryTempFileName(record.tempFileName) ||
            !isSafeOwnedOutputName(actualName)
        ) {
            return CleanupAction.SKIP_UNSAFE
        }
        if (observed == null) return CleanupAction.ALREADY_ABSENT
        if (
            observed.uri != uri ||
            observed.displayName != actualName ||
            observed.relativePath != SCOPED_RELATIVE_PATH ||
            observed.ownerPackageName != expectedOwnerPackageName
        ) {
            return CleanupAction.SKIP_UNSAFE
        }
        return when (observed.isPending) {
            1 -> CleanupAction.DELETE
            0 ->
                if (record.stage == RecoveryStage.DISCARDING) {
                    CleanupAction.DELETE
                } else {
                    CleanupAction.KEEP
                }
            else -> CleanupAction.SKIP_UNSAFE
        }
    }

    fun legacyAction(
        record: TaskRecoveryRecord,
        canonicalOutputDirectory: String,
    ): CleanupAction {
        val uri = record.mediaStoreUri
        val actualName = record.actualOutputDisplayName
        val recordedPath = record.legacyOutputPath
        if (
            uri == null ||
            actualName == null ||
            recordedPath == null ||
            !isAppMediaVideoUri(uri) ||
            !isValidRecoveryTempFileName(record.tempFileName) ||
            !isSafeOwnedOutputName(actualName) ||
            !isDirectCanonicalChild(canonicalOutputDirectory, recordedPath, actualName)
        ) {
            return CleanupAction.SKIP_UNSAFE
        }
        return if (record.stage == RecoveryStage.PUBLISHED) CleanupAction.KEEP else CleanupAction.DELETE
    }

    fun isOwnedLegacyEntry(
        record: TaskRecoveryRecord,
        canonicalOutputDirectory: String,
        observed: LegacyMediaEntry,
    ): Boolean {
        val path = record.legacyOutputPath ?: return false
        val name = record.actualOutputDisplayName ?: return false
        return observed.uri == record.mediaStoreUri &&
            observed.displayName == name &&
            observed.dataPath == path &&
            isDirectCanonicalChild(canonicalOutputDirectory, observed.dataPath, name)
    }

    fun isAppMediaVideoUri(value: String): Boolean = APP_MEDIA_VIDEO_URI.matches(value)

    fun isSafDocumentUri(value: String): Boolean = SAF_DOCUMENT_URI.matches(value)

    fun documentAction(
        record: TaskRecoveryRecord,
        observed: DocumentOutputEntry?,
    ): CleanupAction {
        val uri = record.mediaStoreUri
        if (record.stage == RecoveryStage.ALLOCATED) {
            if (
                uri == null ||
                record.actualOutputDisplayName != null ||
                record.legacyOutputPath != null ||
                !isSafDocumentUri(uri) ||
                !isValidRecoveryTempFileName(record.tempFileName)
            ) {
                return CleanupAction.SKIP_UNSAFE
            }
            if (observed == null) return CleanupAction.ALREADY_ABSENT
            return CleanupAction.SKIP_UNSAFE
        }
        val actualName = record.actualOutputDisplayName
        if (
            uri == null ||
            actualName == null ||
            record.legacyOutputPath != null ||
            !isSafDocumentUri(uri) ||
            !isValidRecoveryTempFileName(record.tempFileName) ||
            !isSafeOwnedOutputName(actualName)
        ) {
            return CleanupAction.SKIP_UNSAFE
        }
        if (observed == null) return CleanupAction.ALREADY_ABSENT
        if (observed.uri != uri || observed.displayName != actualName) {
            return CleanupAction.SKIP_UNSAFE
        }
        return if (record.stage == RecoveryStage.PUBLISHED) CleanupAction.KEEP else CleanupAction.DELETE
    }

    private fun isDirectCanonicalChild(
        canonicalOutputDirectory: String,
        candidatePath: String,
        expectedName: String,
    ): Boolean =
        runCatching {
            val root = File(canonicalOutputDirectory)
            val child = File(candidatePath)
            root.isAbsolute &&
                child.isAbsolute &&
                root.absolutePath == root.canonicalPath &&
                child.absolutePath == child.canonicalPath &&
                child.parentFile?.canonicalPath == root.canonicalPath &&
                child.name == expectedName
        }.getOrDefault(false)

    private fun isSafeOwnedOutputName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 255 &&
            name.endsWith(".mp4", ignoreCase = true) &&
            name.substringBeforeLast('.', missingDelimiterValue = "").isNotBlank() &&
            '/' !in name &&
            '\\' !in name &&
            name.none { it.code < 0x20 || it.code == 0x7f }

    private val APP_MEDIA_VIDEO_URI =
        Regex("^content://media/external/video/media/[0-9]+$")
    private val SAF_DOCUMENT_URI =
        Regex("^content://[^/]+/(?:tree/[^/]+/)?document/.+$")
}

/** Mutable per-run evidence suitable for a single bounded F19 log entry plus optional details. */
class CleanupReport {
    var cacheFilesScanned: Int = 0
    var tempFilesDeleted: Int = 0
    var outputsDeleted: Int = 0
    var mediaRowsDeleted: Int = 0
    var legacyFilesDeleted: Int = 0
    var outputsPreserved: Int = 0
    var alreadyAbsent: Int = 0
    var unsafeItemsSkipped: Int = 0
    var failures: Int = 0
    var journalRecordsCleared: Int = 0
    private val mutableDetails = mutableListOf<String>()
    val details: List<String> get() = mutableDetails.toList()

    fun addDetail(detail: String) {
        if (mutableDetails.size < MAX_DETAILS) mutableDetails += detail
    }

    fun summary(): String =
        "orphan-cleanup " +
            "cacheScanned=$cacheFilesScanned tempDeleted=$tempFilesDeleted " +
            "outputsDeleted=$outputsDeleted rowsDeleted=$mediaRowsDeleted " +
            "legacyFilesDeleted=$legacyFilesDeleted outputsPreserved=$outputsPreserved " +
            "alreadyAbsent=$alreadyAbsent unsafeSkipped=$unsafeItemsSkipped " +
            "failures=$failures journalCleared=$journalRecordsCleared " +
            "details=${details.joinToString(prefix = "[", postfix = "]", separator = "; ")}"

    companion object {
        const val MAX_DETAILS = 50
    }
}

/**
 * Idempotent startup reconciliation. It scans only the direct app-private cache/transcode children
 * and acts on at most the single public output named by a valid recovery record.
 */
class OrphanCleanup(
    context: Context,
    private val recoveryStore: TaskRecoveryStore = TaskRecoveryStore(context),
    private val logger: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun reconcile(activeTempFileName: String? = null): CleanupReport =
        synchronized(RECONCILE_LOCK) {
            val report = CleanupReport()
            if (activeTempFileName != null && !isValidRecoveryTempFileName(activeTempFileName)) {
                report.unsafeItemsSkipped += 1
                report.addDetail("invalid active temp filename was not trusted")
            }
            cleanPrivateTempDirectory(
                activeTempFileName = activeTempFileName?.takeIf(::isValidRecoveryTempFileName),
                report = report,
            )
            try {
                if (recoveryStore.discardInvalidRecord()) {
                    report.journalRecordsCleared += 1
                    report.addDetail(
                        "discarded unreadable recovery journal without touching public media",
                    )
                }
            } catch (error: Throwable) {
                failure(report, "invalid recovery journal could not be cleared", error)
            }
            val record = recoveryStore.load()
            if (record != null) {
                reconcileRecord(record, report)
            }
            log(report.summary())
            report
        }

    private fun cleanPrivateTempDirectory(
        activeTempFileName: String?,
        report: CleanupReport,
    ) {
        val cacheRoot =
            try {
                appContext.cacheDir.canonicalFile
            } catch (error: Throwable) {
                failure(report, "cache root canonicalization failed", error)
                return
            }
        val requestedDirectory = File(cacheRoot, TRANSCODE_DIRECTORY)
        if (!requestedDirectory.exists()) return
        val directory =
            try {
                requestedDirectory.canonicalFile
            } catch (error: Throwable) {
                failure(report, "transcode directory canonicalization failed", error)
                return
            }
        if (directory.parentFile != cacheRoot || !directory.isDirectory) {
            unsafe(report, "transcode directory is not the canonical app cache child")
            return
        }
        val children =
            try {
                directory.listFiles()
            } catch (error: Throwable) {
                failure(report, "transcode directory scan failed", error)
                return
            }
        if (children == null) {
            report.failures += 1
            report.addDetail("transcode directory scan returned no listing")
            return
        }
        children.forEach { child ->
            report.cacheFilesScanned += 1
            val canonicalChild =
                try {
                    child.canonicalFile
                } catch (error: Throwable) {
                    failure(report, "cache child canonicalization failed", error)
                    return@forEach
                }
            if (
                canonicalChild.parentFile != directory ||
                !canonicalChild.isFile ||
                canonicalChild.name != child.name
            ) {
                unsafe(report, "non-file or non-direct cache child skipped: ${child.name}")
                return@forEach
            }
            when (OrphanCleanupPolicy.cacheAction(child.name, activeTempFileName)) {
                CleanupAction.DELETE -> {
                    try {
                        if (child.delete() || !child.exists()) {
                            report.tempFilesDeleted += 1
                            report.addDetail("deleted private temp ${child.name}")
                        } else {
                            report.failures += 1
                            report.addDetail("failed to delete private temp ${child.name}")
                        }
                    } catch (error: Throwable) {
                        failure(report, "private temp delete failed for ${child.name}", error)
                    }
                }
                CleanupAction.KEEP -> report.addDetail("preserved active private temp ${child.name}")
                CleanupAction.SKIP_UNSAFE -> unsafe(report, "unexpected cache child skipped: ${child.name}")
                CleanupAction.ALREADY_ABSENT -> Unit
            }
        }
    }

    private fun reconcileRecord(record: TaskRecoveryRecord, report: CleanupReport) {
        if (record.stage == RecoveryStage.PUBLISHED) {
            report.outputsPreserved += 1
            report.addDetail("preserved completed published output task=${record.taskId}")
            clearRecord(record, report)
            return
        }
        if (record.mediaStoreUri == null && record.legacyOutputPath == null) {
            report.alreadyAbsent += 1
            report.addDetail("task=${record.taskId} had no allocated publication target")
            clearRecord(record, report)
            return
        }
        when {
            record.legacyOutputPath != null -> reconcileLegacyRecord(record, report)
            OrphanCleanupPolicy.isAppMediaVideoUri(record.mediaStoreUri.orEmpty()) ->
                reconcileScopedRecord(record, report)
            OrphanCleanupPolicy.isSafDocumentUri(record.mediaStoreUri.orEmpty()) ->
                reconcileDocumentRecord(record, report)
            else -> unsafe(report, "task=${record.taskId} has an unsupported publication URI")
        }
    }

    private fun reconcileScopedRecord(record: TaskRecoveryRecord, report: CleanupReport) {
        if (!OrphanCleanupPolicy.isAppMediaVideoUri(record.mediaStoreUri.orEmpty())) {
            unsafe(report, "task=${record.taskId} has a non-app MediaStore URI")
            return
        }
        val query =
            queryScopedEntry(record.mediaStoreUri!!, report) ?: run {
                quarantineUnsafeRecord(record, report, "scoped output query unavailable")
                return
            }
        val observed = query.entry
        when (OrphanCleanupPolicy.scopedAction(record, observed, appContext.packageName)) {
            CleanupAction.DELETE -> {
                try {
                    val count = resolver.delete(Uri.parse(record.mediaStoreUri), null, null)
                    var deletionConfirmed = count > 0
                    if (count > 0) {
                        report.outputsDeleted += 1
                        report.mediaRowsDeleted += count
                        report.addDetail("deleted exact pending scoped output task=${record.taskId}")
                    } else {
                        val verification = queryScopedEntry(record.mediaStoreUri, report)
                        if (verification != null && verification.entry == null) {
                            deletionConfirmed = true
                            report.alreadyAbsent += 1
                            report.addDetail(
                                "pending scoped output confirmed absent after zero-row delete task=${record.taskId}",
                            )
                        } else if (verification != null) {
                            report.failures += 1
                            report.addDetail(
                                "scoped output still exists after zero-row delete task=${record.taskId}",
                            )
                        }
                    }
                    if (deletionConfirmed) clearRecord(record, report)
                } catch (error: Throwable) {
                    failure(report, "pending scoped output delete failed task=${record.taskId}", error)
                }
            }
            CleanupAction.KEEP -> {
                report.outputsPreserved += 1
                report.addDetail("preserved completed scoped output task=${record.taskId}")
                clearRecord(record, report)
            }
            CleanupAction.ALREADY_ABSENT -> {
                report.alreadyAbsent += 1
                report.addDetail("scoped output already absent task=${record.taskId}")
                clearRecord(record, report)
            }
            CleanupAction.SKIP_UNSAFE ->
                quarantineUnsafeRecord(
                    record,
                    report,
                    "scoped output ownership could not be proven",
                )
        }
    }

    private fun reconcileDocumentRecord(record: TaskRecoveryRecord, report: CleanupReport) {
        val uri = record.mediaStoreUri!!
        val query =
            queryDocumentEntry(uri, report) ?: run {
                quarantineUnsafeRecord(record, report, "document output query unavailable")
                return
            }
        when (OrphanCleanupPolicy.documentAction(record, query.entry)) {
            CleanupAction.DELETE -> {
                try {
                    val count = resolver.delete(Uri.parse(uri), null, null)
                    var deletionConfirmed = count > 0
                    if (count > 0) {
                        report.outputsDeleted += 1
                        report.addDetail("deleted exact incomplete document output task=${record.taskId}")
                    } else {
                        val verification = queryDocumentEntry(uri, report)
                        if (verification != null && verification.entry == null) {
                            deletionConfirmed = true
                            report.alreadyAbsent += 1
                            report.addDetail("document output confirmed absent task=${record.taskId}")
                        } else if (verification != null) {
                            report.failures += 1
                            report.addDetail("document output still exists after delete task=${record.taskId}")
                        }
                    }
                    if (deletionConfirmed) clearRecord(record, report)
                } catch (error: Throwable) {
                    failure(report, "document output delete failed task=${record.taskId}", error)
                }
            }
            CleanupAction.KEEP -> {
                report.outputsPreserved += 1
                report.addDetail("preserved completed document output task=${record.taskId}")
                clearRecord(record, report)
            }
            CleanupAction.ALREADY_ABSENT -> {
                report.alreadyAbsent += 1
                report.addDetail("document output already absent task=${record.taskId}")
                clearRecord(record, report)
            }
            CleanupAction.SKIP_UNSAFE ->
                quarantineUnsafeRecord(
                    record,
                    report,
                    "document output ownership could not be proven",
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun reconcileLegacyRecord(record: TaskRecoveryRecord, report: CleanupReport) {
        val outputDirectory =
            try {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    OUTPUT_DIRECTORY,
                ).canonicalFile
            } catch (error: Throwable) {
                failure(report, "legacy output directory canonicalization failed", error)
                return
            }
        val action = OrphanCleanupPolicy.legacyAction(record, outputDirectory.path)
        if (action == CleanupAction.SKIP_UNSAFE) {
            quarantineUnsafeRecord(record, report, "legacy output ownership could not be proven")
            return
        }
        val uri = record.mediaStoreUri!!
        val rowQuery =
            queryLegacyEntry(uri, report) ?: run {
                quarantineUnsafeRecord(record, report, "legacy output query unavailable")
                return
            }
        val observed = rowQuery.entry
        if (
            observed != null &&
            !OrphanCleanupPolicy.isOwnedLegacyEntry(record, outputDirectory.path, observed)
        ) {
            quarantineUnsafeRecord(record, report, "legacy MediaStore row ownership mismatch")
            return
        }
        if (action == CleanupAction.KEEP) {
            report.outputsPreserved += 1
            report.addDetail("preserved published legacy output task=${record.taskId}")
            clearRecord(record, report)
            return
        }

        val destination = File(record.legacyOutputPath!!)
        var allClean = true
        var deletedSomething = false
        var allowFileDelete = observed == null
        if (observed != null) {
            try {
                val count = resolver.delete(Uri.parse(uri), null, null)
                if (count > 0) {
                    report.mediaRowsDeleted += count
                    deletedSomething = true
                    allowFileDelete = true
                } else {
                    val verification = queryLegacyEntry(uri, report)
                    if (verification == null) {
                        allClean = false
                    } else if (verification.entry == null) {
                        allowFileDelete = true
                    } else {
                        allClean = false
                        report.failures += 1
                        val identityStillMatches =
                            OrphanCleanupPolicy.isOwnedLegacyEntry(
                                record,
                                outputDirectory.path,
                                verification.entry,
                            )
                        report.addDetail(
                            if (identityStillMatches) {
                                "legacy row still exists after zero-row delete task=${record.taskId}"
                            } else {
                                "legacy row identity changed after zero-row delete task=${record.taskId}"
                            },
                        )
                    }
                }
            } catch (error: Throwable) {
                allClean = false
                failure(report, "legacy MediaStore row delete failed task=${record.taskId}", error)
            }
        }
        if (allowFileDelete) {
            try {
                if (destination.exists()) {
                    if (destination.delete() || !destination.exists()) {
                        report.legacyFilesDeleted += 1
                        deletedSomething = true
                    } else {
                        allClean = false
                        report.failures += 1
                        report.addDetail("legacy output file delete returned false task=${record.taskId}")
                    }
                }
            } catch (error: Throwable) {
                allClean = false
                failure(report, "legacy output file delete failed task=${record.taskId}", error)
            }
        }
        if (deletedSomething) {
            report.outputsDeleted += 1
            report.addDetail("deleted exact interrupted legacy output task=${record.taskId}")
        } else {
            report.alreadyAbsent += 1
            report.addDetail("legacy output already absent task=${record.taskId}")
        }
        if (allClean) clearRecord(record, report)
    }

    private fun queryDocumentEntry(
        uriString: String,
        report: CleanupReport,
    ): EntryQuery<DocumentOutputEntry>? {
        val cursor =
            try {
                resolver.query(
                    Uri.parse(uriString),
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
            } catch (error: Throwable) {
                failure(report, "document output query failed", error)
                return null
            } ?: run {
                report.failures += 1
                report.addDetail("document output query returned null cursor")
                return null
            }
        return cursor.use {
            if (!it.moveToFirst()) return@use EntryQuery(null)
            try {
                EntryQuery(
                    DocumentOutputEntry(
                        uri = uriString,
                        displayName = it.nullableString(OpenableColumns.DISPLAY_NAME),
                    ),
                )
            } catch (error: Throwable) {
                failure(report, "document output row could not be read", error)
                null
            }
        }
    }

    private fun queryScopedEntry(uriString: String, report: CleanupReport): EntryQuery<ScopedMediaEntry>? {
        val cursor =
            try {
                resolver.query(
                    Uri.parse(uriString),
                    arrayOf(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.RELATIVE_PATH,
                        MediaStore.Video.Media.IS_PENDING,
                        MediaStore.Video.Media.OWNER_PACKAGE_NAME,
                    ),
                    null,
                    null,
                    null,
                )
            } catch (error: Throwable) {
                failure(report, "scoped MediaStore query failed", error)
                return null
            } ?: run {
                report.failures += 1
                report.addDetail("scoped MediaStore query returned null cursor")
                return null
            }
        return cursor.use {
            if (!it.moveToFirst()) return@use EntryQuery(null)
            try {
                val pendingColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)
                EntryQuery(
                    ScopedMediaEntry(
                        uri = uriString,
                        displayName = it.nullableString(MediaStore.Video.Media.DISPLAY_NAME),
                        relativePath = it.nullableString(MediaStore.Video.Media.RELATIVE_PATH),
                        isPending = if (it.isNull(pendingColumn)) null else it.getInt(pendingColumn),
                        ownerPackageName = it.nullableString(MediaStore.Video.Media.OWNER_PACKAGE_NAME),
                    ),
                )
            } catch (error: Throwable) {
                failure(report, "scoped MediaStore row could not be read", error)
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyEntry(uriString: String, report: CleanupReport): EntryQuery<LegacyMediaEntry>? {
        if (!OrphanCleanupPolicy.isAppMediaVideoUri(uriString)) {
            unsafe(report, "legacy MediaStore URI is outside the app allocation shape")
            return null
        }
        val cursor =
            try {
                resolver.query(
                    Uri.parse(uriString),
                    arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATA),
                    null,
                    null,
                    null,
                )
            } catch (error: Throwable) {
                failure(report, "legacy MediaStore query failed", error)
                return null
            } ?: run {
                report.failures += 1
                report.addDetail("legacy MediaStore query returned null cursor")
                return null
            }
        return cursor.use {
            if (!it.moveToFirst()) return@use EntryQuery(null)
            try {
                EntryQuery(
                    LegacyMediaEntry(
                        uri = uriString,
                        displayName = it.nullableString(MediaStore.Video.Media.DISPLAY_NAME),
                        dataPath = it.nullableString(MediaStore.Video.Media.DATA),
                    ),
                )
            } catch (error: Throwable) {
                failure(report, "legacy MediaStore row could not be read", error)
                null
            }
        }
    }

    private fun Cursor.nullableString(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun clearRecord(record: TaskRecoveryRecord, report: CleanupReport) {
        try {
            recoveryStore.clear(record.taskId)
            report.journalRecordsCleared += 1
        } catch (error: Throwable) {
            failure(report, "recovery journal clear failed task=${record.taskId}", error)
        }
    }

    private fun quarantineUnsafeRecord(
        record: TaskRecoveryRecord,
        report: CleanupReport,
        reason: String,
    ) {
        unsafe(report, "$reason task=${record.taskId}")
        try {
            recoveryStore.quarantine(record.taskId, reason)
            report.journalRecordsCleared += 1
            report.addDetail("quarantined unsafe recovery evidence task=${record.taskId}")
        } catch (error: Throwable) {
            failure(report, "recovery evidence quarantine failed task=${record.taskId}", error)
        }
    }

    private fun unsafe(report: CleanupReport, detail: String) {
        report.unsafeItemsSkipped += 1
        report.addDetail(detail)
    }

    private fun failure(report: CleanupReport, detail: String, error: Throwable) {
        report.failures += 1
        report.addDetail("$detail (${error.javaClass.simpleName})")
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private data class EntryQuery<T>(val entry: T?)

    private companion object {
        const val TRANSCODE_DIRECTORY = "transcode"
        const val OUTPUT_DIRECTORY = "VideoSlim"
        val RECONCILE_LOCK = Any()
    }
}
