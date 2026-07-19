package com.videoslim.videoslim

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.net.URI

internal fun interface DeleteConsentLauncher {
    @Throws(IntentSender.SendIntentException::class)
    fun launch(
        intentSender: IntentSender,
        onDecision: (approved: Boolean) -> Unit,
    )
}

internal object MediaActionPolicy {
    private const val MAX_URI_LENGTH = 4_096

    fun validatedContentUri(raw: String?): String {
        require(!raw.isNullOrBlank()) { "媒体 URI 不能为空" }
        require(raw.length <= MAX_URI_LENGTH) { "媒体 URI 过长" }
        require(raw == raw.trim()) { "媒体 URI 格式无效" }
        val parsed = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("媒体 URI 格式无效", it) }
        require(!parsed.isOpaque) { "媒体 URI 格式无效" }
        require(parsed.scheme == ContentResolver.SCHEME_CONTENT) { "仅支持系统 content URI" }
        require(!parsed.rawAuthority.isNullOrBlank()) { "媒体 URI 缺少内容提供方" }
        return raw
    }
}

internal class MediaActionsChannel(
    private val activity: Activity,
    private val channel: MethodChannel,
    private val deleteConsentLauncher: DeleteConsentLauncher,
    private val log: (level: String, event: String, details: Map<String, Any?>) -> Unit,
) : MethodChannel.MethodCallHandler {
    private val resolver = activity.contentResolver
    private var pendingDeleteResult: MethodChannel.Result? = null

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        if (call.method !in SUPPORTED_METHODS) {
            result.notImplemented()
            return
        }
        val uri =
            try {
                Uri.parse(MediaActionPolicy.validatedContentUri(call.argument<String>("uri")))
            } catch (error: IllegalArgumentException) {
                result.error("MEDIA_ACTION_FAILED", error.message ?: "媒体 URI 无效", null)
                return
            }
        when (call.method) {
            "openMedia" -> executeImmediate(call.method, uri, result) { openMedia(uri) }
            "shareMedia" -> executeImmediate(call.method, uri, result) { shareMedia(uri) }
            "deleteSource" -> deleteSource(uri, result)
            else -> result.notImplemented()
        }
    }

    fun dispose() {
        channel.setMethodCallHandler(null)
        pendingDeleteResult?.success(mapOf("deleted" to false))
        pendingDeleteResult = null
    }

    private fun executeImmediate(
        method: String,
        uri: Uri,
        result: MethodChannel.Result,
        action: () -> Unit,
    ) {
        try {
            action()
            log("info", "media_action_completed", mapOf("method" to method, "uri" to uri.toString()))
            result.success(emptyMap<String, Any?>())
        } catch (error: Throwable) {
            fail(result, method, uri, error)
        }
    }

    private fun openMedia(uri: Uri) {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(resolver, "VideoSlim 输出", uri)
            }
        require(intent.resolveActivity(activity.packageManager) != null) { "没有可用的视频播放器" }
        activity.startActivity(intent)
    }

    private fun shareMedia(uri: Uri) {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(resolver, "VideoSlim 输出", uri)
            }
        val chooser = Intent.createChooser(sendIntent, "分享压缩后的视频")
        require(chooser.resolveActivity(activity.packageManager) != null) { "没有可用的分享应用" }
        activity.startActivity(chooser)
    }

    private fun deleteSource(
        originalUri: Uri,
        result: MethodChannel.Result,
    ) {
        if (pendingDeleteResult != null) {
            result.error("MEDIA_ACTION_BUSY", "已有删除确认正在进行", null)
            return
        }
        val uri = normalizeMediaUri(originalUri)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    pendingDeleteResult = result
                    val request = MediaStore.createDeleteRequest(resolver, listOf(uri))
                    deleteConsentLauncher.launch(request.intentSender) { approved ->
                        completeDelete(result, uri, approved, retryDelete = false)
                    }
                }

                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> deleteOnAndroidTen(uri, result)
                else -> {
                    val deleted = resolver.delete(uri, null, null) > 0
                    logDelete(uri, deleted)
                    result.success(mapOf("deleted" to deleted))
                }
            }
        } catch (error: Throwable) {
            if (pendingDeleteResult === result) pendingDeleteResult = null
            fail(result, "deleteSource", uri, error)
        }
    }

    private fun deleteOnAndroidTen(
        uri: Uri,
        result: MethodChannel.Result,
    ) {
        try {
            val deleted = resolver.delete(uri, null, null) > 0
            logDelete(uri, deleted)
            result.success(mapOf("deleted" to deleted))
        } catch (error: RecoverableSecurityException) {
            pendingDeleteResult = result
            deleteConsentLauncher.launch(error.userAction.actionIntent.intentSender) { approved ->
                completeDelete(result, uri, approved, retryDelete = true)
            }
        }
    }

    private fun completeDelete(
        result: MethodChannel.Result,
        uri: Uri,
        approved: Boolean,
        retryDelete: Boolean,
    ) {
        if (pendingDeleteResult !== result) return
        pendingDeleteResult = null
        if (!approved) {
            logDelete(uri, false)
            result.success(mapOf("deleted" to false))
            return
        }
        try {
            val deleted = !retryDelete || resolver.delete(uri, null, null) > 0
            logDelete(uri, deleted)
            result.success(mapOf("deleted" to deleted))
        } catch (error: Throwable) {
            fail(result, "deleteSource", uri, error)
        }
    }

    private fun normalizeMediaUri(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        return runCatching { MediaStore.getMediaUri(activity, uri) }.getOrNull() ?: uri
    }

    private fun logDelete(
        uri: Uri,
        deleted: Boolean,
    ) {
        log(
            "info",
            "source_delete_completed",
            mapOf("uri" to uri.toString(), "deleted" to deleted),
        )
    }

    private fun fail(
        result: MethodChannel.Result,
        method: String,
        uri: Uri,
        error: Throwable,
    ) {
        val message =
            when (error) {
                is SecurityException -> "系统未授予此视频的操作权限"
                is IllegalArgumentException -> error.message ?: "系统不支持此媒体操作"
                else -> error.message?.takeIf { it.isNotBlank() } ?: "系统媒体操作失败"
            }
        log(
            "error",
            "media_action_failed",
            mapOf(
                "method" to method,
                "uri" to uri.toString(),
                "errorType" to error.javaClass.name,
                "errorMessage" to error.message,
            ),
        )
        result.error("MEDIA_ACTION_FAILED", message, mapOf("method" to method))
    }

    private companion object {
        val SUPPORTED_METHODS = setOf("openMedia", "shareMedia", "deleteSource")
    }
}
