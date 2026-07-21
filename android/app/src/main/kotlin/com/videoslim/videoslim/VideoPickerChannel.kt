package com.videoslim.videoslim

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    fun newlyAcquiredGrantFlags(
        requestedFlags: Int,
        persistedBefore: Int,
    ): Int =
        requestedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) and
            persistedBefore.inv()

    fun rollbackGrantFlags(
        selectedUri: String,
        previousPreference: String?,
        requestedFlags: Int,
        persistedBefore: Int,
    ): Int =
        if (selectedUri == previousPreference) {
            0
        } else {
            newlyAcquiredGrantFlags(requestedFlags, persistedBefore)
        }

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
    private val ioDispatcher: AppMediaIoDispatcher,
    private val logger: ((String) -> Unit)? = null,
    private val outputLocationStore: OutputLocationOperations =
        OutputLocationStore(activity.applicationContext, logger),
) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, CHANNEL_NAME)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completionCoordinator = MethodChannelCompletionCoordinator(::postToMain)
    private val outputLocationChangeGuard =
        OutputLocationChangeGuard {
            ProcessingRuntime.registry.snapshot()?.state == TaskRuntimeSnapshot.STATE_RUNNING
        }
    private var activeRequest: RequestToken? = null
    private var disposed = false

    private val galleryLauncher =
        activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            completeVideoRequest(uri, returnedFlags = null, RequestKind.GALLERY)
        }

    private val filesLauncher =
        activity.registerForActivityResult(OpenVideoDocumentContract()) { selection ->
            completeVideoRequest(
                uri = selection?.uri,
                returnedFlags = selection?.returnedFlags,
                kind = RequestKind.FILES,
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
                startLauncherRequest(result, RequestKind.GALLERY, "正在打开系统照片选择器") {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                }

            METHOD_PICK_FROM_FILES ->
                startLauncherRequest(result, RequestKind.FILES, "正在打开系统文件选择器") {
                    filesLauncher.launch(arrayOf(VIDEO_MIME_TYPE))
                }

            METHOD_GET_OUTPUT_LOCATION ->
                startIoRequest(
                    result,
                    RequestKind.GET_OUTPUT_LOCATION,
                    MediaIoOperation.OUTPUT_LOCATION_READ,
                    outputLocationStore::getOutputLocation,
                )

            METHOD_CHOOSE_OUTPUT_FOLDER -> chooseOutputFolder(result)

            METHOD_RESET_OUTPUT_LOCATION -> resetOutputLocation(result)
            else -> registerReply(result)?.notImplemented()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        activeRequest = null
        channel.setMethodCallHandler(null)
        galleryLauncher.unregister()
        filesLauncher.unregister()
        outputFolderLauncher.unregister()
        completionCoordinator.dispose()
        log("系统选择器已释放")
    }

    private fun startLauncherRequest(
        result: MethodChannel.Result,
        kind: RequestKind,
        logMessage: String,
        launch: () -> Unit,
    ) {
        val token = claimRequest(result, kind) ?: return
        log(logMessage)
        try {
            // Launcher registration, launch, and Activity Result callbacks stay on main.
            launch()
        } catch (error: Throwable) {
            log("无法打开系统选择器：${error.message.orEmpty()}")
            finishError(token, ERROR_UNKNOWN, "无法打开系统选择器", null)
        }
    }

    private fun <T> startIoRequest(
        result: MethodChannel.Result,
        kind: RequestKind,
        operation: MediaIoOperation,
        block: () -> T,
    ) {
        val token = claimRequest(result, kind) ?: return
        submit(token, operation, block)
    }

    private fun completeVideoRequest(
        uri: Uri?,
        returnedFlags: Int?,
        kind: RequestKind,
    ) {
        val token = activeRequest?.takeIf { it.kind == kind } ?: return
        if (uri == null) {
            log("用户取消了视频选择")
            finishSuccess(token, null)
            return
        }
        submit(token, MediaIoOperation.VIDEO_GRANT_PERSISTENCE) {
            outputLocationStore.recordVideoSelection(uri, returnedFlags)
        }
    }

    private fun chooseOutputFolder(result: MethodChannel.Result) {
        val token = claimRequest(result, RequestKind.OUTPUT_FOLDER) ?: return
        val rejection =
            outputLocationChangeGuard.replaceCustomFolder {
                log("正在打开系统文件夹选择器")
                try {
                    outputFolderLauncher.launch(Unit)
                } catch (error: Throwable) {
                    log("无法打开系统选择器：${error.message.orEmpty()}")
                    finishError(token, ERROR_UNKNOWN, "无法打开系统选择器", null)
                }
            }
        rejection?.let { finishError(token, it.code, it.message, null) }
    }

    private fun completeOutputFolderRequest(selection: OpenDocumentSelection?) {
        val token = activeRequest?.takeIf { it.kind == RequestKind.OUTPUT_FOLDER } ?: return
        if (selection == null) {
            log("用户取消了输出文件夹选择")
            finishSuccess(token, null)
            return
        }
        val rejection =
            outputLocationChangeGuard.replaceCustomFolder {
                submit(token, MediaIoOperation.OUTPUT_FOLDER_REPLACEMENT) {
                    outputLocationStore.replaceOutputFolder(selection)
                }
            }
        rejection?.let { finishError(token, it.code, it.message, null) }
    }

    private fun resetOutputLocation(result: MethodChannel.Result) {
        val token = claimRequest(result, RequestKind.RESET_OUTPUT_LOCATION) ?: return
        val rejection =
            outputLocationChangeGuard.resetToDefault {
                submit(token, MediaIoOperation.OUTPUT_LOCATION_RESET) {
                    outputLocationStore.resetOutputLocation()
                }
            }
        rejection?.let { finishError(token, it.code, it.message, null) }
    }

    private fun <T> submit(
        token: RequestToken,
        operation: MediaIoOperation,
        block: () -> T,
    ) {
        ioDispatcher.submit(operation, block) { outcome ->
            outcome.fold(
                onSuccess = { value -> finishSuccess(token, value) },
                onFailure = { error ->
                    when (error) {
                        is OutputLocationStoreException ->
                            finishError(token, error.code, error.message, null)
                        is AppMediaIoRejectedException ->
                            finishError(token, ERROR_UNKNOWN, "媒体操作繁忙，请稍后重试", null)
                        else -> {
                            log("媒体控制 I/O 失败 ${error.javaClass.simpleName}")
                            finishError(token, ERROR_UNKNOWN, "系统媒体操作失败", null)
                        }
                    }
                },
            )
        }
    }

    private fun claimRequest(
        result: MethodChannel.Result,
        kind: RequestKind,
    ): RequestToken? {
        val reply = registerReply(result) ?: return null
        if (activeRequest != null) {
            reply.error(ERROR_UNKNOWN, "已有系统选择请求正在进行中", null)
            return null
        }
        return RequestToken(kind, reply).also { activeRequest = it }
    }

    private fun registerReply(result: MethodChannel.Result): PickerReply? {
        val completion =
            completionCoordinator.register {
                result.error(ERROR_UNKNOWN, "系统选择器已关闭", null)
            } ?: return null
        return PickerReply(result, completion)
    }

    private fun finishSuccess(
        token: RequestToken,
        value: Any?,
    ) {
        token.reply.complete {
            if (activeRequest !== token) return@complete
            activeRequest = null
            token.reply.result.success(value)
        }
    }

    private fun finishError(
        token: RequestToken,
        code: String,
        message: String,
        details: Any?,
    ) {
        token.reply.complete {
            if (activeRequest !== token) return@complete
            activeRequest = null
            token.reply.result.error(code, message, details)
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun log(message: String) {
        runCatching { logger?.invoke(message) }
    }

    private data class RequestToken(
        val kind: RequestKind,
        val reply: PickerReply,
    )

    private enum class RequestKind {
        GALLERY,
        FILES,
        OUTPUT_FOLDER,
        GET_OUTPUT_LOCATION,
        RESET_OUTPUT_LOCATION,
    }

    private class PickerReply(
        val result: MethodChannel.Result,
        private val completion: MethodChannelCompletionCoordinator.Completion,
    ) {
        fun complete(action: () -> Unit): Boolean = completion.complete(action)

        fun error(
            code: String,
            message: String,
            details: Any?,
        ) {
            completion.complete { result.error(code, message, details) }
        }

        fun notImplemented() {
            completion.complete(result::notImplemented)
        }
    }

    private companion object {
        const val CHANNEL_NAME = "videoslim/picker"
        const val METHOD_PICK_FROM_GALLERY = "pickFromGallery"
        const val METHOD_PICK_FROM_FILES = "pickFromFiles"
        const val METHOD_GET_OUTPUT_LOCATION = "getOutputLocation"
        const val METHOD_CHOOSE_OUTPUT_FOLDER = "chooseOutputFolder"
        const val METHOD_RESET_OUTPUT_LOCATION = "resetOutputLocation"
        const val VIDEO_MIME_TYPE = "video/*"
        const val ERROR_UNKNOWN = OutputLocationStore.ERROR_UNKNOWN
    }
}
