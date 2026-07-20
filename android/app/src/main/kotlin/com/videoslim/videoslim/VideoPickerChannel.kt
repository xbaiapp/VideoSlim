package com.videoslim.videoslim

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

internal data class OpenDocumentSelection(
    val uri: Uri,
    val returnedFlags: Int,
)

internal object PickerGrantPolicy {
    fun offersRead(flags: Int): Boolean =
        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    fun offersPersistable(flags: Int): Boolean =
        flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0

    fun shouldTakePersistableRead(flags: Int): Boolean =
        offersRead(flags) && offersPersistable(flags)
}

internal object OutputFolderGrantPolicy {
    fun offersRead(flags: Int): Boolean =
        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    fun offersWrite(flags: Int): Boolean =
        flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0

    fun canPersistWrite(flags: Int): Boolean =
        offersRead(flags) &&
            offersWrite(flags) &&
            PickerGrantPolicy.offersPersistable(flags)

    fun safeDisplayName(raw: String?): String {
        val normalized =
            raw
                .orEmpty()
                .map { character ->
                    if (character.code < 0x20 || character.code == 0x7F) ' ' else character
                }.joinToString("")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_DISPLAY_NAME_LENGTH)
                .trim()
        return normalized.ifBlank { "已选择的文件夹" }
    }

    private const val MAX_DISPLAY_NAME_LENGTH = 200
}

private class OpenVideoDocumentContract :
    ActivityResultContract<Array<String>, OpenDocumentSelection?>() {
    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (input.size == 1) input[0] else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): OpenDocumentSelection? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return OpenDocumentSelection(uri = uri, returnedFlags = intent.flags)
    }
}

private class OpenOutputFolderContract :
    ActivityResultContract<Unit, OpenDocumentSelection?>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): OpenDocumentSelection? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return OpenDocumentSelection(uri = uri, returnedFlags = intent.flags)
    }
}

internal class VideoPickerChannel(
    activity: ComponentActivity,
    messenger: BinaryMessenger,
    private val logger: ((String) -> Unit)? = null,
) : MethodChannel.MethodCallHandler {
    private val contentResolver: ContentResolver = activity.contentResolver
    private val outputPreferences =
        activity.getSharedPreferences(OUTPUT_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val channel = MethodChannel(messenger, CHANNEL_NAME)
    private var pendingResult: MethodChannel.Result? = null
    private var disposed = false

    private val galleryLauncher =
        activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            completeVideoRequest(uri, returnedFlags = null)
        }

    private val filesLauncher =
        activity.registerForActivityResult(OpenVideoDocumentContract()) { selection ->
            completeVideoRequest(
                uri = selection?.uri,
                returnedFlags = selection?.returnedFlags,
            )
        }

    private val outputFolderLauncher =
        activity.registerForActivityResult(OpenOutputFolderContract()) { selection ->
            completeOutputFolderRequest(selection)
        }

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        when (call.method) {
            METHOD_PICK_FROM_GALLERY ->
                startRequest(result, "正在打开系统照片选择器") {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                }

            METHOD_PICK_FROM_FILES ->
                startRequest(result, "正在打开系统文件选择器") {
                    filesLauncher.launch(arrayOf(VIDEO_MIME_TYPE))
                }

            METHOD_GET_OUTPUT_LOCATION -> getOutputLocation(result)

            METHOD_CHOOSE_OUTPUT_FOLDER ->
                startRequest(result, "正在打开系统文件夹选择器") {
                    outputFolderLauncher.launch(Unit)
                }

            METHOD_RESET_OUTPUT_LOCATION -> resetOutputLocation(result)
            else -> result.notImplemented()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        channel.setMethodCallHandler(null)
        galleryLauncher.unregister()
        filesLauncher.unregister()
        outputFolderLauncher.unregister()
        pendingResult?.error(ERROR_UNKNOWN, "系统选择器已关闭", null)
        pendingResult = null
        log("系统选择器已释放")
    }

    private fun startRequest(
        result: MethodChannel.Result,
        logMessage: String,
        launch: () -> Unit,
    ) {
        if (pendingResult != null) {
            result.error(ERROR_UNKNOWN, "已有系统选择请求正在进行中", null)
            return
        }
        if (disposed) {
            result.error(ERROR_UNKNOWN, "系统选择器已关闭", null)
            return
        }

        pendingResult = result
        log(logMessage)
        try {
            launch()
        } catch (exception: RuntimeException) {
            pendingResult = null
            log("无法打开系统选择器：${exception.message.orEmpty()}")
            result.error(ERROR_UNKNOWN, "无法打开系统选择器", null)
        }
    }

    private fun completeVideoRequest(
        uri: Uri?,
        returnedFlags: Int?,
    ) {
        val result = pendingResult ?: return
        pendingResult = null

        if (uri == null) {
            log("用户取消了视频选择")
            result.success(null)
            return
        }

        if (returnedFlags != null) {
            val readOffered = PickerGrantPolicy.offersRead(returnedFlags)
            val persistableOffered = PickerGrantPolicy.offersPersistable(returnedFlags)
            var takeOutcome = "not_offered"
            if (PickerGrantPolicy.shouldTakePersistableRead(returnedFlags)) {
                takeOutcome =
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        "success"
                    } catch (exception: SecurityException) {
                        "failed_${exception.javaClass.simpleName}"
                    }
            }
            val persistedRead = hasPersistedReadPermission(uri)
            log(
                "系统文件读取授权 authority=${uri.authority ?: "none"} " +
                    "returnedFlags=$returnedFlags readOffered=$readOffered " +
                    "persistableOffered=$persistableOffered takeOutcome=$takeOutcome " +
                    "persistedRead=$persistedRead",
            )
        } else {
            // Android Photo Picker grants are managed by the system and need not appear in
            // ContentResolver.persistedUriPermissions.
            log(
                "系统照片选择授权 authority=${uri.authority ?: "none"} " +
                    "persistedRead=${hasPersistedReadPermission(uri)} " +
                    "taskPreflightRequired=true",
            )
        }
        result.success(uri.toString())
    }

    private fun completeOutputFolderRequest(selection: OpenDocumentSelection?) {
        val result = pendingResult ?: return
        pendingResult = null
        if (selection == null) {
            log("用户取消了输出文件夹选择")
            result.success(null)
            return
        }
        val uri = selection.uri
        val flags = selection.returnedFlags
        if (!DocumentsContract.isTreeUri(uri) || !OutputFolderGrantPolicy.canPersistWrite(flags)) {
            log("输出文件夹未提供可持久化写入授权 uri=$uri flags=$flags")
            result.error(ERROR_OUTPUT_PERMISSION, "所选文件夹没有提供持续写入权限，请选择其他文件夹", null)
            return
        }
        val takeFlags =
            flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (error: SecurityException) {
            log("保存输出文件夹授权失败 uri=$uri error=${error.javaClass.simpleName}")
            result.error(ERROR_OUTPUT_PERMISSION, "系统没有授予这个文件夹的写入权限", null)
            return
        }
        if (!hasPersistedWritePermission(uri)) {
            result.error(ERROR_OUTPUT_PERMISSION, "文件夹写入权限没有保存，请重新选择", null)
            return
        }
        val label = OutputFolderGrantPolicy.safeDisplayName(readTreeDisplayName(uri))
        val previous = outputPreferences.getString(OUTPUT_TREE_URI_KEY, null)
        val saved =
            outputPreferences.edit()
                .putString(OUTPUT_TREE_URI_KEY, uri.toString())
                .putString(OUTPUT_LABEL_KEY, label)
                .commit()
        if (!saved) {
            releaseTreeGrant(uri)
            result.error(ERROR_UNKNOWN, "无法保存文件夹设置", null)
            return
        }
        if (previous != null && previous != uri.toString()) releaseTreeGrant(Uri.parse(previous))
        log("输出文件夹授权已保存 authority=${uri.authority ?: "none"} label=$label")
        result.success(outputLocationMap(uri, label))
    }

    private fun getOutputLocation(result: MethodChannel.Result) {
        val rawUri = outputPreferences.getString(OUTPUT_TREE_URI_KEY, null)
        val label = outputPreferences.getString(OUTPUT_LABEL_KEY, null)
        if (rawUri.isNullOrBlank() || label.isNullOrBlank()) {
            result.success(defaultOutputLocationMap())
            return
        }
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (uri == null || !DocumentsContract.isTreeUri(uri)) {
            result.success(
                linkedMapOf(
                    "kind" to "custom",
                    "label" to "自定义文件夹 > $label",
                    "writable" to false,
                    "treeUri" to rawUri,
                ),
            )
            return
        }
        result.success(outputLocationMap(uri, label))
    }

    private fun resetOutputLocation(result: MethodChannel.Result) {
        val previous = outputPreferences.getString(OUTPUT_TREE_URI_KEY, null)
        if (!outputPreferences.edit().clear().commit()) {
            result.error(ERROR_UNKNOWN, "无法恢复默认保存位置", null)
            return
        }
        previous?.let { runCatching { Uri.parse(it) }.getOrNull()?.let(::releaseTreeGrant) }
        log("输出位置已恢复为 Movies/VideoSlim")
        result.success(defaultOutputLocationMap())
    }

    private fun outputLocationMap(
        uri: Uri,
        label: String,
        writable: Boolean = hasPersistedWritePermission(uri),
    ): Map<String, Any?> =
        linkedMapOf(
            "kind" to "custom",
            "label" to "自定义文件夹 > $label",
            "writable" to writable,
            "treeUri" to uri.toString(),
        )

    private fun defaultOutputLocationMap(): Map<String, Any?> =
        linkedMapOf(
            "kind" to "default",
            "label" to "系统相册 > Movies > VideoSlim",
            "writable" to true,
            "treeUri" to null,
        )

    private fun readTreeDisplayName(treeUri: Uri): String? =
        runCatching {
            val documentUri =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        runCatching {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)

    private fun hasPersistedWritePermission(uri: Uri): Boolean =
        runCatching {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isWritePermission
            }
        }.getOrDefault(false)

    private fun releaseTreeGrant(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { error -> log("释放旧输出文件夹授权失败 ${error.javaClass.simpleName}") }
    }

    private fun log(message: String) {
        runCatching { logger?.invoke(message) }
    }

    private companion object {
        const val CHANNEL_NAME = "videoslim/picker"
        const val METHOD_PICK_FROM_GALLERY = "pickFromGallery"
        const val METHOD_PICK_FROM_FILES = "pickFromFiles"
        const val METHOD_GET_OUTPUT_LOCATION = "getOutputLocation"
        const val METHOD_CHOOSE_OUTPUT_FOLDER = "chooseOutputFolder"
        const val METHOD_RESET_OUTPUT_LOCATION = "resetOutputLocation"
        const val VIDEO_MIME_TYPE = "video/*"
        const val ERROR_UNKNOWN = "UNKNOWN"
        const val ERROR_OUTPUT_PERMISSION = "OUTPUT_PERMISSION_LOST"
        const val OUTPUT_PREFERENCES_NAME = "videoslim_output_location"
        const val OUTPUT_TREE_URI_KEY = "tree_uri"
        const val OUTPUT_LABEL_KEY = "label"
    }
}
