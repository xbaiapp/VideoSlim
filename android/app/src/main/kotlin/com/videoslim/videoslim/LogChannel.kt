package com.videoslim.videoslim

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Native endpoint for the videoslim/logs Flutter method channel. */
internal class LogChannel(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val dispatcher: AppLogDispatcher,
) : MethodChannel.MethodCallHandler {
    companion object {
        const val CHANNEL_NAME = "videoslim/logs"
        private const val CHOOSER_TITLE = "分享 VideoSlim 调试日志"
    }

    private val channel = MethodChannel(messenger, CHANNEL_NAME)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completionCoordinator = MethodChannelCompletionCoordinator(::postToMain)

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "append" -> append(call, result)
            "readAll" -> readAll(result)
            "shareAll" -> shareAll(result)
            else -> registerReply(result, "log_method_failed")?.notImplemented()
        }
    }

    fun dispose() {
        channel.setMethodCallHandler(null)
        completionCoordinator.dispose()
    }

    private fun append(call: MethodCall, result: MethodChannel.Result) {
        val reply = registerReply(result, "log_append_failed") ?: return
        val entry = when (val arguments = call.arguments) {
            is String -> arguments
            is Map<*, *> -> arguments["entry"] as? String
            else -> null
        }
        if (entry == null) {
            reply.error(
                "invalid_arguments",
                "append requires a string entry",
                null,
            )
            return
        }
        reply.submit("log_append_failed") {
            dispatcher.append(entry) { outcome ->
                outcome.fold(
                    onSuccess = { reply.success(null) },
                    onFailure = { error -> reply.error("log_append_failed", error) },
                )
            }
        }
    }

    private fun readAll(result: MethodChannel.Result) {
        val reply = registerReply(result, "log_read_failed") ?: return
        reply.submit("log_read_failed") {
            dispatcher.readAll { outcome ->
                outcome.fold(
                    onSuccess = reply::success,
                    onFailure = { error -> reply.error("log_read_failed", error) },
                )
            }
        }
    }

    private fun shareAll(result: MethodChannel.Result) {
        val reply = registerReply(result, "log_share_failed") ?: return
        reply.submit("log_share_failed") {
            dispatcher.createShareSnapshot { outcome ->
                outcome.fold(
                    onSuccess = { snapshot -> prepareShare(snapshot, reply) },
                    onFailure = { error -> reply.error("log_share_failed", error) },
                )
            }
        }
    }

    /** Runs on the dispatcher writer after all preceding writes and snapshot I/O complete. */
    private fun prepareShare(
        snapshot: java.io.File,
        reply: PendingReply,
    ) {
        try {
            val contentUri =
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    snapshot,
                )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                clipData = ClipData.newRawUri("VideoSlim 调试日志", contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserIntent = Intent.createChooser(sendIntent, CHOOSER_TITLE)
            reply.launchShare(chooserIntent)
        } catch (error: Throwable) {
            reply.error("log_share_failed", error)
        }
    }

    private fun registerReply(
        result: MethodChannel.Result,
        disposalErrorCode: String,
    ): PendingReply? {
        val completion = completionCoordinator.register {
            val error = IllegalStateException("log channel is disposed")
            result.error(
                disposalErrorCode,
                error.message,
                errorDetails(error),
            )
        } ?: return null
        return PendingReply(result, completion)
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun errorDetails(error: Throwable): Map<String, String> =
        mapOf(
            "type" to error.javaClass.name,
            "stack" to error.stackTraceToString().take(8192),
        )

    private inner class PendingReply(
        private val result: MethodChannel.Result,
        private val completion: MethodChannelCompletionCoordinator.Completion,
    ) {
        fun submit(
            synchronousErrorCode: String,
            submission: () -> Unit,
        ) {
            completion.submit(
                submission = submission,
                onSynchronousFailure = { error -> deliverError(synchronousErrorCode, error) },
            )
        }

        fun success(value: Any?) {
            completion.complete { result.success(value) }
        }

        fun notImplemented() {
            completion.complete(result::notImplemented)
        }

        fun error(
            code: String,
            error: Throwable,
        ) {
            error(code, error.message ?: error.javaClass.simpleName, errorDetails(error))
        }

        fun error(
            code: String,
            message: String?,
            details: Any?,
        ) {
            completion.complete { result.error(code, message, details) }
        }

        fun launchShare(intent: Intent) {
            completion.complete {
                try {
                    activity.startActivity(intent)
                } catch (error: Throwable) {
                    result.error(
                        "log_share_failed",
                        error.message ?: error.javaClass.simpleName,
                        errorDetails(error),
                    )
                    return@complete
                }
                result.success(null)
            }
        }

        private fun deliverError(
            code: String,
            error: Throwable,
        ) {
            result.error(
                code,
                error.message ?: error.javaClass.simpleName,
                errorDetails(error),
            )
        }
    }
}
