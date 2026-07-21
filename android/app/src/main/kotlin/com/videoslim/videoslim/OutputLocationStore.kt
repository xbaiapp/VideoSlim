package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread

internal class OutputLocationStoreException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface OutputLocationOperations {
    @WorkerThread
    fun recordVideoSelection(
        uri: Uri,
        returnedFlags: Int?,
    ): String

    @WorkerThread
    fun replaceOutputFolder(selection: OpenDocumentSelection): Map<String, Any?>

    @WorkerThread
    fun getOutputLocation(): Map<String, Any?>

    @WorkerThread
    fun resetOutputLocation(): Map<String, Any?>
}

/** Blocking worker-only boundary for output preferences, tree providers, and persisted grants. */
internal class OutputLocationStore(
    context: Context,
    private val logger: ((String) -> Unit)? = null,
) : OutputLocationOperations {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver by lazy(LazyThreadSafetyMode.NONE) { appContext.contentResolver }
    private val preferences by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSharedPreferences(OUTPUT_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @WorkerThread
    override fun recordVideoSelection(
        uri: Uri,
        returnedFlags: Int?,
    ): String {
        if (returnedFlags != null) {
            val readOffered = PickerGrantPolicy.offersRead(returnedFlags)
            val persistableOffered = PickerGrantPolicy.offersPersistable(returnedFlags)
            var takeOutcome = "not_offered"
            if (PickerGrantPolicy.shouldTakePersistableRead(returnedFlags)) {
                takeOutcome =
                    try {
                        resolver.takePersistableUriPermission(
                            uri,
                            returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        "success"
                    } catch (exception: SecurityException) {
                        "failed_${exception.javaClass.simpleName}"
                    }
            }
            log(
                "系统文件读取授权 authority=${uri.authority ?: "none"} " +
                    "returnedFlags=$returnedFlags readOffered=$readOffered " +
                    "persistableOffered=$persistableOffered takeOutcome=$takeOutcome " +
                    "persistedRead=${hasPersistedReadPermission(uri)}",
            )
        } else {
            // Photo Picker grants are system-managed but persisted-grant inspection is still I/O.
            log(
                "系统照片选择授权 authority=${uri.authority ?: "none"} " +
                    "persistedRead=${hasPersistedReadPermission(uri)} " +
                    "taskPreflightRequired=true",
            )
        }
        return uri.toString()
    }

    @WorkerThread
    override fun replaceOutputFolder(selection: OpenDocumentSelection): Map<String, Any?> {
        val uri = selection.uri
        val flags = selection.returnedFlags
        if (!DocumentsContract.isTreeUri(uri) || !OutputFolderGrantPolicy.canPersistWrite(flags)) {
            log("输出文件夹未提供可持久化写入授权 uri=$uri flags=$flags")
            throw failure(
                ERROR_OUTPUT_PERMISSION,
                "所选文件夹没有提供持续写入权限，请选择其他文件夹",
            )
        }
        val previous = preferences.getString(OUTPUT_TREE_URI_KEY, null)
        val takeFlags =
            flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val persistedBefore = persistedGrantFlags(uri)
        try {
            resolver.takePersistableUriPermission(uri, takeFlags)
        } catch (error: SecurityException) {
            log("保存输出文件夹授权失败 uri=$uri error=${error.javaClass.simpleName}")
            throw failure(ERROR_OUTPUT_PERMISSION, "系统没有授予这个文件夹的写入权限", error)
        }
        if (!hasPersistedReadPermission(uri) || !hasPersistedWritePermission(uri)) {
            rollbackNewTreeGrant(uri, takeFlags, persistedBefore, previous)
            throw failure(ERROR_OUTPUT_PERMISSION, "文件夹读写权限没有保存，请重新选择")
        }
        val destination =
            try {
                verifyOutputTree(uri)
            } catch (error: Throwable) {
                rollbackNewTreeGrant(uri, takeFlags, persistedBefore, previous)
                log("输出文件夹能力验证失败 uri=$uri error=${error.javaClass.simpleName}")
                throw failure(
                    ERROR_OUTPUT_PERMISSION,
                    "所选文件夹无法创建新文件，请选择其他文件夹",
                    error,
                )
            }
        val saved =
            preferences.edit()
                .putString(OUTPUT_TREE_URI_KEY, uri.toString())
                .putString(OUTPUT_LABEL_KEY, destination.label)
                .commit()
        if (!saved) {
            rollbackNewTreeGrant(uri, takeFlags, persistedBefore, previous)
            throw failure(ERROR_UNKNOWN, "无法保存文件夹设置")
        }
        // The durable new preference is the replacement boundary; only then release the old grant.
        if (previous != null && previous != uri.toString()) releaseTreeGrant(Uri.parse(previous))
        log("输出文件夹授权已保存 authority=${uri.authority ?: "none"} label=${destination.label}")
        return outputLocationMap(uri, destination.label)
    }

    @WorkerThread
    override fun getOutputLocation(): Map<String, Any?> {
        val rawUri = preferences.getString(OUTPUT_TREE_URI_KEY, null)
        val label = preferences.getString(OUTPUT_LABEL_KEY, null)
        if (rawUri.isNullOrBlank() || label.isNullOrBlank()) return defaultOutputLocationMap()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (uri == null || !DocumentsContract.isTreeUri(uri)) {
            return linkedMapOf(
                "kind" to "custom",
                "label" to "自定义文件夹 > $label",
                "writable" to false,
                "treeUri" to rawUri,
            )
        }
        return outputLocationMap(uri, label)
    }

    @WorkerThread
    override fun resetOutputLocation(): Map<String, Any?> {
        val previous = preferences.getString(OUTPUT_TREE_URI_KEY, null)
        if (previous != null) {
            try {
                val recoveryStore = TaskRecoveryStore(appContext)
                val record = recoveryStore.load()
                if (record?.stage == RecoveryStage.PUBLISHED) {
                    recoveryStore.clear(record.taskId)
                } else if (record?.mediaStoreUri?.let(OrphanCleanupPolicy::isSafDocumentUri) == true) {
                    recoveryStore.quarantine(record.taskId, "output tree grant reset")
                }
            } catch (error: Throwable) {
                log("重置输出位置前隔离恢复证据失败 ${error.javaClass.simpleName}")
                throw failure(
                    ERROR_UNKNOWN,
                    "仍有未完成文件需要保留恢复信息，请稍后重试",
                    error,
                )
            }
        }
        if (!preferences.edit().clear().commit()) {
            throw failure(ERROR_UNKNOWN, "无法恢复默认保存位置")
        }
        previous?.let { runCatching { Uri.parse(it) }.getOrNull()?.let(::releaseTreeGrant) }
        log("输出位置已恢复为 Movies/VideoSlim")
        return defaultOutputLocationMap()
    }

    private fun outputLocationMap(
        uri: Uri,
        label: String,
    ): Map<String, Any?> =
        linkedMapOf(
            "kind" to "custom",
            "label" to "自定义文件夹 > $label",
            "writable" to (hasPersistedReadPermission(uri) && hasPersistedWritePermission(uri)),
            "treeUri" to uri.toString(),
        )

    private fun verifyOutputTree(treeUri: Uri): VerifiedOutputTree {
        val documentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val cursor =
            resolver.query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_FLAGS,
                ),
                null,
                null,
                null,
            ) ?: throw IllegalStateException("Output tree query returned null")
        return cursor.use {
            if (!it.moveToFirst() || it.isNull(0) || it.isNull(1)) {
                throw IllegalStateException("Output tree capabilities are incomplete")
            }
            val rawLabel = it.getString(0)
            if (rawLabel.isBlank()) {
                throw IllegalStateException("Output tree display name is blank")
            }
            val flags = it.getInt(1)
            if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                throw IllegalStateException("Output tree does not support file creation")
            }
            VerifiedOutputTree(OutputFolderGrantPolicy.safeDisplayName(rawLabel))
        }
    }

    private fun rollbackNewTreeGrant(
        uri: Uri,
        takeFlags: Int,
        persistedBefore: Int,
        previousPreference: String?,
    ) {
        val newlyAcquired =
            OutputFolderGrantPolicy.rollbackGrantFlags(
                selectedUri = uri.toString(),
                previousPreference = previousPreference,
                requestedFlags = takeFlags,
                persistedBefore = persistedBefore,
            )
        if (newlyAcquired != 0) releaseTreeGrant(uri, newlyAcquired)
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        runCatching {
            resolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)

    private fun hasPersistedWritePermission(uri: Uri): Boolean =
        runCatching {
            resolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isWritePermission
            }
        }.getOrDefault(false)

    private fun persistedGrantFlags(uri: Uri): Int =
        runCatching {
            resolver.persistedUriPermissions
                .firstOrNull { permission -> permission.uri == uri }
                ?.let { permission ->
                    (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                } ?: 0
        }.getOrDefault(0)

    private fun releaseTreeGrant(
        uri: Uri,
        flags: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, flags)
        }.onFailure { error -> log("释放旧输出文件夹授权失败 ${error.javaClass.simpleName}") }
    }

    private fun failure(
        code: String,
        message: String,
        cause: Throwable? = null,
    ) = OutputLocationStoreException(code, message, cause)

    private fun log(message: String) {
        runCatching { logger?.invoke(message) }
    }

    private data class VerifiedOutputTree(val label: String)

    internal companion object {
        const val ERROR_UNKNOWN = "UNKNOWN"
        const val ERROR_OUTPUT_PERMISSION = "OUTPUT_PERMISSION_LOST"
        const val OUTPUT_PREFERENCES_NAME = "videoslim_output_location"
        const val OUTPUT_TREE_URI_KEY = "tree_uri"
        const val OUTPUT_LABEL_KEY = "label"

        fun defaultOutputLocationMap(): Map<String, Any?> =
            linkedMapOf(
                "kind" to "default",
                "label" to "系统相册 > Movies > VideoSlim",
                "writable" to true,
                "treeUri" to null,
            )
    }
}
