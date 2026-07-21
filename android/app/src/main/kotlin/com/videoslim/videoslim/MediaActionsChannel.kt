package com.videoslim.videoslim

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
        if (raw.isNullOrBlank()) throw MediaActionUserException("媒体 URI 不能为空")
        if (raw.length > MAX_URI_LENGTH) throw MediaActionUserException("媒体 URI 过长")
        if (raw != raw.trim()) throw MediaActionUserException("媒体 URI 格式无效")
        val parsed =
            runCatching { URI(raw) }
                .getOrElse { throw MediaActionUserException("媒体 URI 格式无效") }
        if (parsed.isOpaque) throw MediaActionUserException("媒体 URI 格式无效")
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT) {
            throw MediaActionUserException("仅支持系统 content URI")
        }
        if (parsed.rawAuthority.isNullOrBlank()) {
            throw MediaActionUserException("媒体 URI 缺少内容提供方")
        }
        return raw
    }
}

internal class MediaActionUserException(
    val userMessage: String,
) : IllegalArgumentException(userMessage)

internal data class MediaActionFailure(
    val code: String,
    val message: String,
)

internal object MediaActionFailurePolicy {
    const val ERROR_CODE = "MEDIA_ACTION_FAILED"
    const val GENERIC_MESSAGE = "系统媒体操作失败"

    fun from(error: Throwable): MediaActionFailure =
        MediaActionFailure(
            code = ERROR_CODE,
            message =
                when (error) {
                    is AppMediaIoRejectedException -> "媒体操作繁忙，请稍后重试"
                    is SecurityException -> "系统未授予此媒体文件的操作权限"
                    is MediaActionUserException -> error.userMessage
                    else -> GENERIC_MESSAGE
                },
        )
}

internal enum class MediaActionMediaKind(
    val mimeType: String,
    val chooserTitle: String,
) {
    VIDEO("video/mp4", "分享压缩视频"),
    AUDIO("audio/mp4", "分享提取的音频"),
    ;

    companion object {
        fun fromResolvedMimeType(value: String?): MediaActionMediaKind =
            entries.firstOrNull { it.mimeType == value }
                ?: throw MediaActionUserException("仅支持 VideoSlim 生成的 MP4 视频或 M4A 音频")
    }
}

internal class MediaActionsChannel(
    private val activity: Activity,
    private val channel: MethodChannel,
    private val deleteConsentLauncher: DeleteConsentLauncher,
    private val ioDispatcher: AppMediaIoDispatcher,
    private val log: (level: String, event: String, details: Map<String, Any?>) -> Unit,
) : MethodChannel.MethodCallHandler {
    private val resolver = activity.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completionCoordinator = MethodChannelCompletionCoordinator(::postToMain)
    private var pendingDelete: DeleteToken? = null
    private var disposed = false

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val reply =
            registerReply(
                result,
                if (call.method == METHOD_DELETE) {
                    "媒体操作通道已关闭，删除结果可能未知"
                } else {
                    "媒体操作通道已关闭"
                },
            ) ?: return
        if (call.method !in SUPPORTED_METHODS) {
            reply.notImplemented()
            return
        }
        val uri =
            try {
                Uri.parse(MediaActionPolicy.validatedContentUri(call.argument<String>("uri")))
            } catch (error: MediaActionUserException) {
                reply.error(MediaActionFailurePolicy.ERROR_CODE, error.userMessage, null)
                return
            }
        when (call.method) {
            METHOD_OPEN -> prepareActivityAction(call.method, uri, reply, ::prepareOpenIntent)
            METHOD_SHARE -> prepareActivityAction(call.method, uri, reply, ::prepareShareIntent)
            METHOD_DELETE -> deleteSource(uri, reply)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        channel.setMethodCallHandler(null)
        pendingDelete = null
        completionCoordinator.dispose()
    }

    private fun prepareActivityAction(
        method: String,
        uri: Uri,
        reply: PendingMediaReply,
        prepare: (Uri) -> Intent,
    ) {
        val operation =
            if (method == METHOD_OPEN) {
                MediaIoOperation.MEDIA_OPEN_PREPARATION
            } else {
                MediaIoOperation.MEDIA_SHARE_PREPARATION
            }
        ioDispatcher.submit(operation, { prepare(uri) }) { outcome ->
            outcome.fold(
                onSuccess = { intent ->
                    reply.complete {
                        try {
                            // Activity launch and MethodChannel delivery remain on the main looper.
                            activity.startActivity(intent)
                            log(
                                "info",
                                "media_action_completed",
                                mapOf("method" to method, "uri" to uri.toString()),
                            )
                            reply.result.success(emptyMap<String, Any?>())
                        } catch (error: Throwable) {
                            deliverFailure(reply.result, method, uri, error)
                        }
                    }
                },
                onFailure = { error -> reply.failure(method, uri, error) },
            )
        }
    }

    /** MIME lookup, ClipData/provider preparation, and intent resolution all run on media I/O. */
    private fun prepareOpenIntent(uri: Uri): Intent {
        val mediaKind = mediaKind(uri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mediaKind.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(resolver, "VideoSlim 输出", uri)
            if (resolveActivity(activity.packageManager) == null) {
                throw MediaActionUserException("没有可用的媒体播放器")
            }
        }
    }

    private fun prepareShareIntent(uri: Uri): Intent {
        val mediaKind = mediaKind(uri)
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = mediaKind.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(resolver, "VideoSlim 输出", uri)
            }
        return Intent.createChooser(sendIntent, mediaKind.chooserTitle).also { chooser ->
            if (chooser.resolveActivity(activity.packageManager) == null) {
                throw MediaActionUserException("没有可用的分享应用")
            }
        }
    }

    private fun mediaKind(uri: Uri): MediaActionMediaKind =
        MediaActionMediaKind.fromResolvedMimeType(resolver.getType(uri))

    private fun deleteSource(originalUri: Uri, reply: PendingMediaReply) {
        if (pendingDelete != null) {
            reply.error("MEDIA_ACTION_BUSY", "已有删除确认正在进行", null)
            return
        }
        val token = DeleteToken(originalUri, reply)
        pendingDelete = token
        submitDeletePreflight(token)
    }

    private fun submitDeletePreflight(token: DeleteToken) {
        ioDispatcher.submit(MediaIoOperation.MEDIA_DELETE_PREFLIGHT, {
            val uri = normalizeMediaUri(token.originalUri)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    DeletePreflight.Consent(
                        uri = uri,
                        intentSender = MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender,
                        retryDelete = false,
                    )
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> deleteOnAndroidTen(uri)
                else -> DeletePreflight.Completed(uri, resolver.delete(uri, null, null) > 0)
            }
        }) { outcome ->
            outcome.fold(
                onSuccess = { preflight -> handleDeletePreflight(token, preflight) },
                onFailure = { error -> finishDeleteFailure(token, token.originalUri, error) },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteOnAndroidTen(uri: Uri): DeletePreflight =
        try {
            DeletePreflight.Completed(uri, resolver.delete(uri, null, null) > 0)
        } catch (error: RecoverableSecurityException) {
            DeletePreflight.Consent(
                uri = uri,
                intentSender = error.userAction.actionIntent.intentSender,
                retryDelete = true,
            )
        }

    private fun handleDeletePreflight(token: DeleteToken, preflight: DeletePreflight) {
        when (preflight) {
            is DeletePreflight.Completed ->
                finishDeleteSuccess(token, preflight.uri, preflight.deleted)
            is DeletePreflight.Consent ->
                postToMain { launchDeleteConsent(token, preflight) }
        }
    }

    private fun launchDeleteConsent(token: DeleteToken, consent: DeletePreflight.Consent) {
        if (!isActive(token, DeleteStage.PREFLIGHT)) return
        token.stage = DeleteStage.CONSENT
        token.normalizedUri = consent.uri
        token.retryDelete = consent.retryDelete
        try {
            deleteConsentLauncher.launch(consent.intentSender) { approved ->
                handleDeleteConsent(token, approved)
            }
        } catch (error: Throwable) {
            finishDeleteFailure(token, consent.uri, error)
        }
    }

    private fun handleDeleteConsent(token: DeleteToken, approved: Boolean) {
        if (!isActive(token, DeleteStage.CONSENT)) return
        val uri = token.normalizedUri ?: token.originalUri
        if (!approved) {
            finishDeleteSuccess(token, uri, false)
            return
        }
        if (!token.retryDelete) {
            // Android 11+ performs deletion as part of the approved system request.
            finishDeleteSuccess(token, uri, true)
            return
        }
        token.stage = DeleteStage.RETRY
        ioDispatcher.submit(
            MediaIoOperation.MEDIA_DELETE_RETRY,
            { resolver.delete(uri, null, null) > 0 },
        ) { outcome ->
            outcome.fold(
                onSuccess = { deleted -> finishDeleteSuccess(token, uri, deleted) },
                onFailure = { error -> finishDeleteFailure(token, uri, error) },
            )
        }
    }

    private fun finishDeleteSuccess(token: DeleteToken, uri: Uri, deleted: Boolean) {
        token.reply.complete {
            if (pendingDelete !== token) return@complete
            pendingDelete = null
            logDelete(uri, deleted)
            token.reply.result.success(mapOf("deleted" to deleted))
        }
    }

    private fun finishDeleteFailure(token: DeleteToken, uri: Uri, error: Throwable) {
        token.reply.complete {
            if (pendingDelete !== token) return@complete
            pendingDelete = null
            deliverFailure(token.reply.result, METHOD_DELETE, uri, error)
        }
    }

    private fun isActive(token: DeleteToken, expectedStage: DeleteStage): Boolean =
        !disposed && pendingDelete === token && token.stage == expectedStage

    private fun normalizeMediaUri(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        return runCatching { MediaStore.getMediaUri(activity, uri) }.getOrNull() ?: uri
    }

    private fun logDelete(uri: Uri, deleted: Boolean) {
        log(
            "info",
            "source_delete_completed",
            mapOf("uri" to uri.toString(), "deleted" to deleted),
        )
    }

    private fun registerReply(
        result: MethodChannel.Result,
        disposalMessage: String,
    ): PendingMediaReply? {
        val completion =
            completionCoordinator.register {
                result.error("MEDIA_ACTION_FAILED", disposalMessage, null)
            } ?: return null
        return PendingMediaReply(result, completion)
    }

    private fun deliverFailure(
        result: MethodChannel.Result,
        method: String,
        uri: Uri,
        error: Throwable,
    ) {
        val failure = MediaActionFailurePolicy.from(error)
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
        result.error(failure.code, failure.message, mapOf("method" to method))
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private inner class PendingMediaReply(
        val result: MethodChannel.Result,
        private val completion: MethodChannelCompletionCoordinator.Completion,
    ) {
        fun complete(action: () -> Unit): Boolean = completion.complete(action)

        fun error(code: String, message: String, details: Any?) {
            completion.complete { result.error(code, message, details) }
        }

        fun failure(method: String, uri: Uri, error: Throwable) {
            completion.complete { deliverFailure(result, method, uri, error) }
        }

        fun notImplemented() {
            completion.complete(result::notImplemented)
        }
    }

    private data class DeleteToken(
        val originalUri: Uri,
        val reply: PendingMediaReply,
        var stage: DeleteStage = DeleteStage.PREFLIGHT,
        var normalizedUri: Uri? = null,
        var retryDelete: Boolean = false,
    )

    private sealed interface DeletePreflight {
        data class Completed(val uri: Uri, val deleted: Boolean) : DeletePreflight

        data class Consent(
            val uri: Uri,
            val intentSender: IntentSender,
            val retryDelete: Boolean,
        ) : DeletePreflight
    }

    private enum class DeleteStage {
        PREFLIGHT,
        CONSENT,
        RETRY,
    }

    private companion object {
        const val METHOD_OPEN = "openMedia"
        const val METHOD_SHARE = "shareMedia"
        const val METHOD_DELETE = "deleteSource"
        val SUPPORTED_METHODS = setOf(METHOD_OPEN, METHOD_SHARE, METHOD_DELETE)
    }
}
