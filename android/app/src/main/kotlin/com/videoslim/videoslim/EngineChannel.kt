package com.videoslim.videoslim

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal typealias LegacyWritePermissionRequester = ((Boolean) -> Unit) -> Unit
internal typealias NotificationPermissionRequester = ((Boolean) -> Unit) -> Unit

internal class EngineChannel(
    private val context: Context,
    messenger: BinaryMessenger,
    private val metadataReader: VideoMetadataReader,
    private val transcodeEngine: TranscodeEngine,
    private val requestLegacyWritePermission: LegacyWritePermissionRequester,
    private val requestNotificationPermission: NotificationPermissionRequester,
    private val logger: (String) -> Unit = {},
    private val metadataExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null
    private var waitingForLegacyPermission = false
    private var waitingForNotificationPermission = false
    private var disposed = false
    private val registryObserver: (TaskRuntimeSnapshot) -> Unit = { snapshot ->
        runCatching { eventSink?.success(snapshot.toProgressMap()) }
            .onFailure { error -> log("progress delivery failed ${error.stackTraceToString()}") }
        log("progress=${snapshot.toProgressMap()}")
    }

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed) {
            result.error(EngineErrorCode.UNKNOWN.wireName, "原生引擎已关闭", null)
            return
        }
        log("method=${call.method} arguments=${call.arguments}")
        when (call.method) {
            "getVideoInfo" -> getVideoInfo(call.arguments, result)
            "getCapabilities" -> getCapabilities(call.arguments, result)
            "getTaskSnapshot" -> getTaskSnapshot(call.arguments, result)
            "process" -> process(call.arguments, result)
            "extractAudio" ->
                replyError(
                    result,
                    EngineFailure(EngineErrorCode.UNKNOWN, "M1 暂不支持音频提取"),
                    null,
                )
            "cancel" -> cancel(call.arguments, result)
            else -> result.notImplemented()
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
        waitingForLegacyPermission = false
        waitingForNotificationPermission = false
        ProcessingRuntime.registry.removeObserver(registryObserver)
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        metadataExecutor.shutdownNow()
        transcodeEngine.dispose()
    }

    private fun getVideoInfo(arguments: Any?, result: MethodChannel.Result) {
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
                replyError(result, EngineFailure(EngineErrorCode.UNKNOWN, "视频信息参数无效"), error)
                return
            }
        metadataExecutor.execute {
            try {
                val response = metadataReader.read(uri).toChannelMap()
                mainHandler.post {
                    if (!disposed) {
                        log("method=getVideoInfo response=$response")
                        result.success(response)
                    }
                }
            } catch (error: Throwable) {
                val failure = metadataFailure(error)
                mainHandler.post { if (!disposed) replyError(result, failure, error) }
            }
        }
    }

    private fun getCapabilities(arguments: Any?, result: MethodChannel.Result) {
        try {
            requireExactMap(arguments, emptySet())
            val response = transcodeEngine.getCapabilities()
            log("method=getCapabilities response=$response")
            result.success(response)
        } catch (error: Throwable) {
            replyError(result, EngineFailure(EngineErrorCode.UNKNOWN), error)
        }
    }

    private fun getTaskSnapshot(arguments: Any?, result: MethodChannel.Result) {
        try {
            requireExactMap(arguments, emptySet())
            result.success(ProcessingRuntime.registry.snapshot()?.toSnapshotMap())
        } catch (error: Throwable) {
            replyError(result, EngineFailure(EngineErrorCode.UNKNOWN), error)
        }
    }

    private fun process(arguments: Any?, result: MethodChannel.Result) {
        val request =
            try {
                ProcessRequest.parse(arguments)
            } catch (error: ProcessRequestException) {
                replyError(result, error.error, error)
                return
            } catch (error: Throwable) {
                replyError(result, EngineFailure(EngineErrorCode.UNKNOWN, "压缩参数无效"), error)
                return
            }
        try {
            transcodeEngine.validateOutputDestination(request.outputTreeUri)
        } catch (error: EngineOperationException) {
            replyError(result, error.failure, error)
            return
        }
        if (waitingForLegacyPermission || waitingForNotificationPermission) {
            replyError(
                result,
                EngineFailure(EngineErrorCode.UNKNOWN, "正在等待系统权限，请勿重复提交"),
                null,
            )
            return
        }
        val requestNotificationAndLaunch: () -> Unit = {
            waitingForNotificationPermission = true
            requestNotificationPermission notificationPermission@{ notificationGranted ->
                if (disposed) return@notificationPermission
                waitingForNotificationPermission = false
                if (!notificationGranted) {
                    replyError(
                        result,
                        EngineFailure(
                            EngineErrorCode.UNKNOWN,
                            "后台压缩需要通知权限，请在系统设置中允许通知",
                        ),
                        null,
                    )
                    return@notificationPermission
                }
                try {
                    val taskId = ProcessingRuntime.launch(context, arguments, request)
                    val response = mapOf("taskId" to taskId)
                    log("method=process response=$response")
                    result.success(response)
                } catch (error: EngineOperationException) {
                    replyError(result, error.failure, error)
                } catch (error: Throwable) {
                    replyError(result, EngineErrorMapper.fromThrowable(error), error)
                }
            }
        }
        if (request.outputTreeUri != null) {
            requestNotificationAndLaunch()
            return
        }
        waitingForLegacyPermission = true
        requestLegacyWritePermission legacyPermission@{ granted ->
            if (disposed) return@legacyPermission
            waitingForLegacyPermission = false
            if (!granted) {
                replyError(
                    result,
                    EngineFailure(
                        EngineErrorCode.UNKNOWN,
                        "Android 8–9 保存到相册需要存储写入权限",
                    ),
                    null,
                )
                return@legacyPermission
            }
            requestNotificationAndLaunch()
        }
    }

    private fun cancel(arguments: Any?, result: MethodChannel.Result) {
        val taskId =
            try {
                requireExactMap(arguments, setOf("taskId"))["taskId"] as? String
                    ?: throw IllegalArgumentException("taskId 必须是字符串")
            } catch (error: Throwable) {
                replyError(result, EngineFailure(EngineErrorCode.UNKNOWN, "取消参数无效"), error)
                return
            }
        try {
            ProcessingRuntime.cancel(context, taskId)
            result.success(emptyMap<String, Any?>())
        } catch (error: Throwable) {
            replyError(result, EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun replyError(
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
                    EngineFailure(
                        EngineErrorCode.SOURCE_CORRUPTED,
                        error.message,
                    )
                } else {
                    EngineFailure(
                        EngineErrorCode.UNKNOWN,
                        error.message,
                    )
                }
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun requireExactMap(arguments: Any?, keys: Set<String>): Map<*, *> {
        val map = arguments as? Map<*, *> ?: throw IllegalArgumentException("参数必须是对象")
        if (map.keys.any { it !is String } || map.keys != keys) {
            throw IllegalArgumentException("参数字段必须严格为 ${keys.sorted()}")
        }
        return map
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        const val METHOD_CHANNEL = "videoslim/engine"
        const val EVENT_CHANNEL = "videoslim/progress"
        const val STATE_RUNNING = "running"
    }
}
