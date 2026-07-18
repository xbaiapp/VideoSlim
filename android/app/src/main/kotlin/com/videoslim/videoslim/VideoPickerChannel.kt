package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

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
            completeRequest(uri)
        }

    private val filesLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            completeRequest(uri)
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

    private fun completeRequest(uri: Uri?) {
        val result = pendingResult ?: return
        pendingResult = null

        if (uri == null) {
            log("用户取消了视频选择")
            result.success(null)
            return
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            log("已尝试保留视频 URI 的读取权限")
        } catch (exception: SecurityException) {
            // Photo Picker providers do not have to offer persistable grants.
            log("视频 URI 不支持持久读取授权，将使用当前读取授权")
        }
        result.success(uri.toString())
    }

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
