package com.videoslim.videoslim

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

internal typealias LegacyWritePermissionRequester = ((Boolean) -> Unit) -> Unit
internal typealias NotificationPermissionRequester = ((Boolean) -> Unit) -> Unit

internal fun routeEngineSnapshotLog(
    snapshot: TaskRuntimeSnapshot,
    message: String,
    protectedLogger: (String) -> Unit,
    progressLogger: (String, String) -> Unit,
) {
    if (snapshot.isTerminal) {
        protectedLogger(message)
    } else {
        progressLogger(snapshot.taskId, message)
    }
}

internal class EngineChannel(
    private val context: Context,
    messenger: BinaryMessenger,
    private val metadataReader: VideoMetadataReader,
    private val transcodeEngine: TranscodeEngine,
    private val requestLegacyWritePermission: LegacyWritePermissionRequester,
    private val requestNotificationPermission: NotificationPermissionRequester,
    private val ioDispatcher: AppMediaIoDispatcher,
    private val logger: (String) -> Unit = {},
    private val progressLogger: (String, String) -> Unit = { _, _ -> },
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completionCoordinator = MethodChannelCompletionCoordinator(::postToMain)
    private val audioMetadataReader = AudioMetadataReader(context)
    private var eventSink: EventChannel.EventSink? = null
    private var activeLaunch: LaunchToken? = null
    private var disposed = false
    private val registryObserver: (TaskRuntimeSnapshot) -> Unit = { snapshot ->
        runCatching { eventSink?.success(snapshot.toProgressMap()) }
            .onFailure { error -> log("progress delivery failed ${error.stackTraceToString()}") }
        val message = "progress=${snapshot.toProgressMap()}"
        routeEngineSnapshotLog(snapshot, message, ::log, ::logProgress)
    }

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val reply = registerReply(result) ?: return
        log("method=${call.method} arguments=${call.arguments}")
        when (call.method) {
            "getVideoInfo" -> getVideoInfo(call.arguments, reply)
            "getAudioInfo" -> getAudioInfo(call.arguments, reply)
            "getCapabilities" -> getCapabilities(call.arguments, reply)
            "getTaskSnapshot" -> getTaskSnapshot(call.arguments, reply)
            "process" -> process(call.arguments, reply)
            "extractAudio" -> extractAudio(call.arguments, reply)
            "cancel" -> cancel(call.arguments, reply)
            else -> reply.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        ProcessingRuntime.registry.removeObserver(registryObserver)
        eventSink = events
        ProcessingRuntime.registry.addObserver(registryObserver)
    }

    override fun onCancel(arguments: Any?) {
        ProcessingRuntime.registry.removeObserver(registryObserver)
        eventSink = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        activeLaunch = null
        ProcessingRuntime.registry.removeObserver(registryObserver)
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        completionCoordinator.dispose()
        transcodeEngine.dispose()
    }

    private fun getVideoInfo(arguments: Any?, reply: PendingEngineReply) {
        val uri =
            try {
                val value =
                    requireExactMap(arguments, setOf("uri"))["uri"] as? String
                        ?: throw IllegalArgumentException("uri 必须是字符串")
                if (!isValidContentVideoUri(value)) {
                    throw IllegalArgumentException("uri 必须是有效的 content:// URI")
                }
                value
            } catch (error: Throwable) {
                reply.error(EngineFailure(EngineErrorCode.UNKNOWN, "视频信息参数无效"), error)
                return
            }
        submitIo(reply, MediaIoOperation.VIDEO_METADATA, {
            metadataReader.read(uri).toChannelMap()
        }) { outcome ->
            outcome.fold(
                onSuccess = { response ->
                    reply.success(response) {
                        log("method=getVideoInfo response=$response")
                    }
                },
                onFailure = { error -> reply.error(metadataFailure(error), error) },
            )
        }
    }

    private fun getAudioInfo(arguments: Any?, reply: PendingEngineReply) {
        val uri =
            try {
                val value =
                    requireExactMap(arguments, setOf("uri"))["uri"] as? String
                        ?: throw IllegalArgumentException("uri 必须是字符串")
                if (!isValidContentVideoUri(value)) {
                    throw IllegalArgumentException("uri 必须是有效的 content:// URI")
                }
                value
            } catch (error: Throwable) {
                reply.error(EngineFailure(EngineErrorCode.UNKNOWN, "音频信息参数无效"), error)
                return
            }
        val verifiedResponse =
            takeVerifiedPublishedAudioInfo(
                outputUri = uri,
                snapshot = ProcessingRuntime.registry.snapshot(),
                cache = ProcessingRuntime.verifiedAudioInfoCache,
            )
        if (verifiedResponse != null) {
            reply.success(verifiedResponse) {
                log("method=getAudioInfo response=$verifiedResponse source=verified_output")
            }
            return
        }
        submitIo(reply, MediaIoOperation.AUDIO_METADATA, {
            audioMetadataReader.read(uri).toChannelMap()
        }) { outcome ->
            outcome.fold(
                onSuccess = { response ->
                    reply.success(response) {
                        log("method=getAudioInfo response=$response")
                    }
                },
                onFailure = { error -> reply.error(audioMetadataFailure(error), error) },
            )
        }
    }

    private fun getCapabilities(arguments: Any?, reply: PendingEngineReply) {
        try {
            requireExactMap(arguments, emptySet())
            val response = transcodeEngine.getCapabilities()
            reply.success(response) { log("method=getCapabilities response=$response") }
        } catch (error: Throwable) {
            reply.error(EngineFailure(EngineErrorCode.UNKNOWN), error)
        }
    }

    private fun getTaskSnapshot(arguments: Any?, reply: PendingEngineReply) {
        try {
            requireExactMap(arguments, emptySet())
            reply.success(ProcessingRuntime.registry.snapshot()?.toSnapshotMap())
        } catch (error: Throwable) {
            reply.error(EngineFailure(EngineErrorCode.UNKNOWN), error)
        }
    }

    private fun process(arguments: Any?, reply: PendingEngineReply) {
        val request =
            try {
                ProcessRequest.parse(arguments)
            } catch (error: ProcessRequestException) {
                reply.error(error.error, error)
                return
            } catch (error: Throwable) {
                reply.error(EngineFailure(EngineErrorCode.UNKNOWN, "压缩参数无效"), error)
                return
            }
        startLaunch(
            token =
                LaunchToken(
                    kind = LaunchKind.VIDEO,
                    outputTreeUri = request.outputTreeUri,
                    arguments = arguments,
                    processRequest = request,
                    audioRequest = null,
                    reply = reply,
                ),
        )
    }

    private fun extractAudio(arguments: Any?, reply: PendingEngineReply) {
        val request =
            try {
                AudioExtractRequest.parse(arguments)
            } catch (error: ProcessRequestException) {
                reply.error(error.error, error)
                return
            } catch (error: Throwable) {
                reply.error(EngineFailure(EngineErrorCode.UNKNOWN, "音频提取参数无效"), error)
                return
            }
        startLaunch(
            token =
                LaunchToken(
                    kind = LaunchKind.AUDIO,
                    outputTreeUri = request.outputTreeUri,
                    arguments = arguments,
                    processRequest = null,
                    audioRequest = request,
                    reply = reply,
                ),
        )
    }

    /** The main-owned token covers validation, both permission handoffs, and service launch. */
    private fun startLaunch(token: LaunchToken) {
        if (activeLaunch != null) {
            token.reply.error(
                EngineFailure(EngineErrorCode.UNKNOWN, "正在等待系统权限，请勿重复提交"),
                null,
            )
            return
        }
        activeLaunch = token
        ioDispatcher.submit(
            MediaIoOperation.OUTPUT_DESTINATION_VALIDATION,
            { transcodeEngine.validateOutputDestination(token.outputTreeUri) },
        ) { outcome ->
            outcome.fold(
                onSuccess = {
                    // Validation is blocking I/O; every permission launcher and service launch is main-owned.
                    postToMain { continueAfterValidation(token) }
                },
                onFailure = { error -> finishLaunchError(token, launchFailure(error), error) },
            )
        }
    }

    private fun continueAfterValidation(token: LaunchToken) {
        if (!isActive(token, LaunchStage.VALIDATING)) return
        if (token.outputTreeUri != null) {
            requestNotificationAndLaunch(token)
            return
        }
        token.stage = LaunchStage.LEGACY_PERMISSION
        try {
            requestLegacyWritePermission { granted -> handleLegacyPermission(token, granted) }
        } catch (error: Throwable) {
            finishLaunchError(token, EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun handleLegacyPermission(token: LaunchToken, granted: Boolean) {
        if (!isActive(token, LaunchStage.LEGACY_PERMISSION)) return
        if (!granted) {
            val message =
                if (token.kind == LaunchKind.VIDEO) {
                    "Android 8–9 保存到相册需要存储写入权限"
                } else {
                    "Android 8–9 保存音频需要存储写入权限"
                }
            finishLaunchError(token, EngineFailure(EngineErrorCode.UNKNOWN, message), null)
            return
        }
        requestNotificationAndLaunch(token)
    }

    private fun requestNotificationAndLaunch(token: LaunchToken) {
        if (activeLaunch !== token || disposed) return
        token.stage = LaunchStage.NOTIFICATION_PERMISSION
        try {
            requestNotificationPermission { granted -> handleNotificationPermission(token, granted) }
        } catch (error: Throwable) {
            finishLaunchError(token, EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun handleNotificationPermission(token: LaunchToken, granted: Boolean) {
        if (!isActive(token, LaunchStage.NOTIFICATION_PERMISSION)) return
        if (!granted) {
            val operation = if (token.kind == LaunchKind.VIDEO) "压缩" else "提取音频"
            finishLaunchError(
                token,
                EngineFailure(
                    EngineErrorCode.UNKNOWN,
                    "后台${operation}需要通知权限，请在系统设置中允许通知",
                ),
                null,
            )
            return
        }
        token.stage = LaunchStage.LAUNCHING
        try {
            val taskId =
                when (token.kind) {
                    LaunchKind.VIDEO ->
                        ProcessingRuntime.launch(
                            context,
                            token.arguments,
                            checkNotNull(token.processRequest),
                        )
                    LaunchKind.AUDIO ->
                        ProcessingRuntime.launchAudio(
                            context,
                            token.arguments,
                            checkNotNull(token.audioRequest),
                        )
                }
            val response = mapOf("taskId" to taskId)
            finishLaunch(token) {
                log("method=${token.kind.methodName} response=$response")
                token.reply.result.success(response)
            }
        } catch (error: EngineOperationException) {
            finishLaunchError(token, error.failure, error)
        } catch (error: Throwable) {
            finishLaunchError(token, EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun cancel(arguments: Any?, reply: PendingEngineReply) {
        val taskId =
            try {
                requireExactMap(arguments, setOf("taskId"))["taskId"] as? String
                    ?: throw IllegalArgumentException("taskId 必须是字符串")
            } catch (error: Throwable) {
                reply.error(EngineFailure(EngineErrorCode.UNKNOWN, "取消参数无效"), error)
                return
            }
        try {
            ProcessingRuntime.cancel(context, taskId)
            reply.success(emptyMap<String, Any?>())
        } catch (error: Throwable) {
            reply.error(EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun <T> submitIo(
        reply: PendingEngineReply,
        operation: MediaIoOperation,
        block: () -> T,
        callback: (Result<T>) -> Unit,
    ) {
        try {
            ioDispatcher.submit(operation, block, callback)
        } catch (error: Throwable) {
            reply.error(EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun launchFailure(error: Throwable): EngineFailure =
        when (error) {
            is EngineOperationException -> error.failure
            is AppMediaIoRejectedException ->
                EngineFailure(EngineErrorCode.UNKNOWN, "媒体操作繁忙，请稍后重试")
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun isActive(token: LaunchToken, expectedStage: LaunchStage): Boolean =
        !disposed && activeLaunch === token && token.stage == expectedStage

    private fun finishLaunchError(
        token: LaunchToken,
        failure: EngineFailure,
        error: Throwable?,
    ) {
        finishLaunch(token) { token.reply.deliverError(failure, error) }
    }

    private fun finishLaunch(token: LaunchToken, action: () -> Unit) {
        token.reply.complete {
            if (activeLaunch !== token) return@complete
            activeLaunch = null
            action()
        }
    }

    private fun registerReply(result: MethodChannel.Result): PendingEngineReply? {
        val completion =
            completionCoordinator.register {
                result.error(
                    EngineErrorCode.UNKNOWN.wireName,
                    "原生引擎已关闭",
                    null,
                )
            } ?: return null
        return PendingEngineReply(result, completion)
    }

    private fun deliverError(
        result: MethodChannel.Result,
        failure: EngineFailure,
        error: Throwable?,
    ) {
        val stack = error?.stackTraceToString()
        val details =
            mapOf(
                "code" to failure.code.wireName,
                "nativeStacktrace" to stack,
                "cause" to error?.cause?.toString(),
            )
        log(
            "error code=${failure.code.wireName} message=${failure.message} " +
                "details=$details",
        )
        result.error(failure.code.wireName, failure.message, details)
    }

    private fun metadataFailure(error: Throwable): EngineFailure =
        when (error) {
            is VideoMetadataException ->
                if (error.code == VideoMetadataException.SOURCE_CORRUPTED) {
                    EngineFailure(EngineErrorCode.SOURCE_CORRUPTED, error.message)
                } else {
                    EngineFailure(EngineErrorCode.UNKNOWN, error.message)
                }
            is AppMediaIoRejectedException ->
                EngineFailure(EngineErrorCode.UNKNOWN, "媒体操作繁忙，请稍后重试")
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun audioMetadataFailure(error: Throwable): EngineFailure =
        when (error) {
            is AudioMetadataException ->
                when (error.code) {
                    AudioMetadataException.SOURCE_CORRUPTED ->
                        EngineFailure(EngineErrorCode.SOURCE_CORRUPTED, error.message)
                    AudioMetadataException.NO_AUDIO_TRACK ->
                        EngineFailure(EngineErrorCode.AUDIO_TRACK_MISSING, error.message)
                    else -> EngineFailure(EngineErrorCode.UNKNOWN, error.message)
                }
            is AppMediaIoRejectedException ->
                EngineFailure(EngineErrorCode.UNKNOWN, "媒体操作繁忙，请稍后重试")
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun requireExactMap(arguments: Any?, keys: Set<String>): Map<*, *> {
        val map = arguments as? Map<*, *> ?: throw IllegalArgumentException("参数必须是对象")
        if (map.keys.any { it !is String } || map.keys != keys) {
            throw IllegalArgumentException("参数字段必须严格为 ${keys.sorted()}")
        }
        return map
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private fun logProgress(taskId: String, message: String) {
        runCatching { progressLogger(taskId, message) }
    }

    private inner class PendingEngineReply(
        val result: MethodChannel.Result,
        private val completion: MethodChannelCompletionCoordinator.Completion,
    ) {
        fun complete(action: () -> Unit): Boolean = completion.complete(action)

        fun success(value: Any?, beforeDelivery: () -> Unit = {}) {
            completion.complete {
                beforeDelivery()
                result.success(value)
            }
        }

        fun error(failure: EngineFailure, error: Throwable?) {
            completion.complete { deliverError(failure, error) }
        }

        fun deliverError(failure: EngineFailure, error: Throwable?) {
            this@EngineChannel.deliverError(result, failure, error)
        }

        fun notImplemented() {
            completion.complete(result::notImplemented)
        }
    }

    private data class LaunchToken(
        val kind: LaunchKind,
        val outputTreeUri: String?,
        val arguments: Any?,
        val processRequest: ProcessRequest?,
        val audioRequest: AudioExtractRequest?,
        val reply: PendingEngineReply,
        var stage: LaunchStage = LaunchStage.VALIDATING,
    )

    private enum class LaunchKind(val methodName: String) {
        VIDEO("process"),
        AUDIO("extractAudio"),
    }

    private enum class LaunchStage {
        VALIDATING,
        LEGACY_PERMISSION,
        NOTIFICATION_PERMISSION,
        LAUNCHING,
    }

    private companion object {
        const val METHOD_CHANNEL = "videoslim/engine"
        const val EVENT_CHANNEL = "videoslim/progress"
    }
}
