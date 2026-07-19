package com.videoslim.videoslim

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
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

internal class VideoPickerChannel(
    activity: ComponentActivity,
    messenger: BinaryMessenger,
    private val logger: ((String) -> Unit)? = null,
) : MethodChannel.MethodCallHandler {
    private val contentResolver: ContentResolver = activity.contentResolver
    private val channel = MethodChannel(messenger, CHANNEL_NAME)
    private var pendingResult: MethodChannel.Result? = null
    private var disposed = false

    private val galleryLauncher =
        activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            completeRequest(uri, returnedFlags = null)
        }

    private val filesLauncher =
        activity.registerForActivityResult(OpenVideoDocumentContract()) { selection ->
            completeRequest(
                uri = selection?.uri,
                returnedFlags = selection?.returnedFlags,
            )
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

            else -> result.notImplemented()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        channel.setMethodCallHandler(null)
        galleryLauncher.unregister()
        filesLauncher.unregister()
        pendingResult?.error(ERROR_UNKNOWN, "视频选择器已关闭", null)
        pendingResult = null
        log("视频选择器已释放")
    }

    private fun startRequest(
        result: MethodChannel.Result,
        logMessage: String,
        launch: () -> Unit,
    ) {
        if (pendingResult != null) {
            result.error(ERROR_UNKNOWN, "已有视频选择请求正在进行中", null)
            return
        }
        if (disposed) {
            result.error(ERROR_UNKNOWN, "视频选择器已关闭", null)
            return
        }

        pendingResult = result
        log(logMessage)
        try {
            launch()
        } catch (exception: RuntimeException) {
            pendingResult = null
            log("无法打开视频选择器：${exception.message.orEmpty()}")
            result.error(ERROR_UNKNOWN, "无法打开视频选择器", null)
        }
    }

    private fun completeRequest(
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

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        runCatching {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)

    private fun log(message: String) {
        runCatching { logger?.invoke(message) }
    }

    private companion object {
        const val CHANNEL_NAME = "videoslim/picker"
        const val METHOD_PICK_FROM_GALLERY = "pickFromGallery"
        const val METHOD_PICK_FROM_FILES = "pickFromFiles"
        const val VIDEO_MIME_TYPE = "video/*"
        const val ERROR_UNKNOWN = "UNKNOWN"
    }
}
