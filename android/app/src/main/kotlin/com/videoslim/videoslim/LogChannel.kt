package com.videoslim.videoslim

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Native endpoint for the videoslim/logs Flutter method channel. */
class LogChannel(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val store: AppLogStore = AppLogStore(activity),
) : MethodChannel.MethodCallHandler {
    companion object {
        const val CHANNEL_NAME = "videoslim/logs"
        private const val CHOOSER_TITLE = "分享 VideoSlim 调试日志"
    }

    private val channel = MethodChannel(messenger, CHANNEL_NAME)

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "append" -> append(call, result)
            "readAll" -> readAll(result)
            "shareAll" -> shareAll(result)
            else -> result.notImplemented()
        }
    }

    fun dispose() {
        channel.setMethodCallHandler(null)
    }

    private fun append(call: MethodCall, result: MethodChannel.Result) {
        try {
            val entry = when (val arguments = call.arguments) {
                is String -> arguments
                is Map<*, *> -> arguments["entry"] as? String
                else -> null
            }
            if (entry == null) {
                result.error(
                    "invalid_arguments",
                    "append requires a string entry",
                    null,
                )
                return
            }
            store.append(entry)
            result.success(null)
        } catch (error: Throwable) {
            reportError("log_append_failed", error, result)
        }
    }

    private fun readAll(result: MethodChannel.Result) {
        try {
            result.success(store.readAll())
        } catch (error: Throwable) {
            reportError("log_read_failed", error, result)
        }
    }

    private fun shareAll(result: MethodChannel.Result) {
        try {
            val snapshot = store.createShareSnapshot()
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
            activity.startActivity(Intent.createChooser(sendIntent, CHOOSER_TITLE))
            result.success(null)
        } catch (error: Throwable) {
            reportError("log_share_failed", error, result)
        }
    }

    private fun reportError(
        code: String,
        error: Throwable,
        result: MethodChannel.Result,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        val details = mapOf(
            "type" to error.javaClass.name,
            "stack" to error.stackTraceToString().take(8192),
        )
        result.error(code, message, details)
    }
}
