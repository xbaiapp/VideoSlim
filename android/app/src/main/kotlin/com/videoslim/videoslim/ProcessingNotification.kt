package com.videoslim.videoslim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import kotlin.math.roundToInt

internal data class ProcessingNotificationText(
    val title: String,
    val body: String,
    val progress: Int,
    val ongoing: Boolean,
    val showCancel: Boolean,
) {
    companion object {
        fun from(snapshot: TaskRuntimeSnapshot): ProcessingNotificationText {
            val progress = snapshot.percent.roundToInt().coerceIn(0, 100)
            val isAudio = snapshot.taskKind == TaskKind.AUDIO_EXTRACTION
            return when (snapshot.state) {
                TaskRuntimeSnapshot.STATE_RUNNING ->
                    if (isAudio) audioRunning(snapshot, progress) else running(snapshot, progress)

                TaskRuntimeSnapshot.STATE_SUCCESS ->
                    ProcessingNotificationText(
                        title = if (isAudio) "音频已提取并保存" else "视频已压缩并保存",
                        body =
                            if (isAudio) {
                                "已保存到 ${snapshot.outputLocationLabel}"
                            } else {
                                "已保存到 ${snapshot.outputLocationLabel} · ${encodingModeText(snapshot)}"
                            },
                        progress = 100,
                        ongoing = false,
                        showCancel = false,
                    )

                TaskRuntimeSnapshot.STATE_FAILED ->
                    ProcessingNotificationText(
                        title = if (isAudio) "没能完成音频提取" else "没能完成压缩",
                        body =
                            if (isAudio) {
                                audioFailureBody(snapshot.errorCode)
                            } else {
                                "${failureBody(snapshot)} · ${encodingModeText(snapshot)}"
                            },
                        progress = progress,
                        ongoing = false,
                        showCancel = false,
                    )

                TaskRuntimeSnapshot.STATE_CANCELLED ->
                    ProcessingNotificationText(
                        title = if (isAudio) "音频提取已取消" else "压缩已取消",
                        body =
                            if (isAudio) {
                                "原视频和已保存文件没有被修改"
                            } else {
                                "原视频没有被修改"
                            },
                        progress = progress,
                        ongoing = false,
                        showCancel = false,
                    )

                else -> throw IllegalArgumentException("Unknown task state: ${snapshot.state}")
            }
        }

        private fun audioRunning(
            snapshot: TaskRuntimeSnapshot,
            progress: Int,
        ): ProcessingNotificationText {
            val converting = audioMode(snapshot) == AudioExtractMode.AAC.wireName
            val title =
                when (snapshot.phase) {
                    TaskRuntimeSnapshot.PHASE_PREPARING -> "正在准备音频提取"
                    TaskRuntimeSnapshot.PHASE_PUBLISHING -> "正在保存音频"
                    TaskRuntimeSnapshot.PHASE_CANCELLING -> "正在取消音频提取"
                    else -> if (converting) "正在转换音频" else "正在提取音频"
                }
            val body =
                when (snapshot.phase) {
                    TaskRuntimeSnapshot.PHASE_PREPARING -> "正在检查音轨和可用空间"
                    TaskRuntimeSnapshot.PHASE_PUBLISHING -> "正在保存到 ${snapshot.outputLocationLabel}"
                    TaskRuntimeSnapshot.PHASE_CANCELLING -> "正在停止任务并清理未完成文件"
                    else -> "已完成 $progress%"
                }
            return ProcessingNotificationText(
                title = title,
                body = body,
                progress = progress,
                ongoing = true,
                showCancel = snapshot.phase != TaskRuntimeSnapshot.PHASE_CANCELLING,
            )
        }

        private fun running(
            snapshot: TaskRuntimeSnapshot,
            progress: Int,
        ): ProcessingNotificationText =
            when (snapshot.phase) {
                TaskRuntimeSnapshot.PHASE_PREPARING ->
                    ProcessingNotificationText(
                        title =
                            if (snapshot.automaticSoftwareDecoderRetry) {
                                "正在兼容重试视频"
                            } else {
                                "正在准备视频"
                            },
                        body =
                            if (snapshot.automaticSoftwareDecoderRetry) {
                                "硬件读取失败，已自动改用兼容方式"
                            } else {
                                "即将开始压缩 · ${encodingModeText(snapshot)}"
                            },
                        progress = progress,
                        ongoing = true,
                        showCancel = true,
                    )
                TaskRuntimeSnapshot.PHASE_ENCODING ->
                    ProcessingNotificationText(
                        title =
                            if (snapshot.automaticSoftwareDecoderRetry) {
                                "正在兼容重试视频"
                            } else {
                                "正在压缩视频"
                            },
                        body =
                            if (snapshot.automaticSoftwareDecoderRetry) {
                                "已完成 $progress% · 软件读取"
                            } else {
                                "已完成 $progress% · ${encodingModeText(snapshot)}"
                            },
                        progress = progress,
                        ongoing = true,
                        showCancel = true,
                    )
                TaskRuntimeSnapshot.PHASE_PUBLISHING ->
                    ProcessingNotificationText(
                        title = "正在保存视频",
                        body = "正在保存到 ${snapshot.outputLocationLabel} · ${encodingModeText(snapshot)}",
                        progress = progress,
                        ongoing = true,
                        showCancel = true,
                    )
                TaskRuntimeSnapshot.PHASE_CANCELLING ->
                    ProcessingNotificationText(
                        title = "正在取消",
                        body = "正在清理未完成文件",
                        progress = progress,
                        ongoing = true,
                        showCancel = false,
                    )
                TaskRuntimeSnapshot.PHASE_FINISHED ->
                    ProcessingNotificationText(
                        title = "正在确认保存结果",
                        body = "请稍候",
                        progress = progress,
                        ongoing = true,
                        showCancel = false,
                    )
                else -> throw IllegalArgumentException("Unknown task phase: ${snapshot.phase}")
            }

        private fun audioMode(snapshot: TaskRuntimeSnapshot): String? =
            ((snapshot.retryRequest?.get("audio") as? Map<*, *>)?.get("mode") as? String)

        private fun audioFailureBody(errorCode: String?): String =
            when (errorCode) {
                EngineErrorCode.INSUFFICIENT_STORAGE.wireName -> "存储空间不足，请释放空间后重试"
                EngineErrorCode.SOURCE_PERMISSION_LOST.wireName -> "无法继续读取视频，请重新选择文件"
                EngineErrorCode.SOURCE_UNAVAILABLE.wireName -> "所选视频已移动、删除或暂时不可用"
                EngineErrorCode.SOURCE_PROVIDER_FAILED.wireName -> "手机无法持续读取音轨，请重新选择或稍后重试"
                EngineErrorCode.SOURCE_CORRUPTED.wireName -> "无法读取这个视频，文件可能损坏或格式不受支持"
                EngineErrorCode.AUDIO_TRACK_MISSING.wireName -> "这个视频没有可提取的音轨"
                EngineErrorCode.AUDIO_COPY_UNSUPPORTED.wireName -> "源音轨不是 AAC，请打开 VideoSlim 改用 AAC 转码"
                EngineErrorCode.AUDIO_CHANNEL_LAYOUT_UNSUPPORTED.wireName -> "暂不支持超过双声道的音频"
                EngineErrorCode.AUDIO_DECODING_FAILED.wireName -> "手机无法读取源音频，原视频没有被修改"
                EngineErrorCode.AUDIO_ENCODING_FAILED.wireName -> "手机没能完成 AAC 音频编码，原视频没有被修改"
                EngineErrorCode.AUDIO_OUTPUT_INVALID.wireName -> "提取结果不完整，没有保存公共输出"
                EngineErrorCode.OUTPUT_PERMISSION_LOST.wireName -> "保存文件夹权限已失效，请重新选择"
                else -> "音频提取失败，请打开 VideoSlim 查看详情"
            }

        private fun failureBody(snapshot: TaskRuntimeSnapshot): String =
            when (snapshot.errorCode) {
                EngineErrorCode.INSUFFICIENT_STORAGE.wireName -> "存储空间不足，请释放空间后重试"
                EngineErrorCode.SOURCE_PERMISSION_LOST.wireName -> "无法继续读取视频，请打开 VideoSlim 重新选择"
                EngineErrorCode.SOURCE_UNAVAILABLE.wireName -> "所选视频已移动、删除或暂时不可用"
                EngineErrorCode.SOURCE_PROVIDER_FAILED.wireName -> "手机无法持续读取视频，请重新选择或稍后重试"
                EngineErrorCode.SOURCE_CORRUPTED.wireName -> "无法处理这个视频，文件可能损坏或格式不受支持"
                EngineErrorCode.INVALID_TRIM.wireName -> "时间裁剪范围无效，请打开 VideoSlim 重新选择"
                EngineErrorCode.VIDEO_DECODING_FAILED.wireName ->
                    if (snapshot.videoDecoderMode == VideoDecoderMode.HARDWARE.wireName) {
                        "视频读取方式未能完成，原视频没有被修改。请打开 VideoSlim 查看详情"
                    } else {
                        "软件读取方式未能完成，原视频没有被修改。可打开 VideoSlim 查看详情"
                    }
                EngineErrorCode.VIDEO_FORMAT_UNSUPPORTED.wireName -> "这台手机暂时无法读取这种视频格式"
                EngineErrorCode.COMPATIBILITY_DECODER_UNAVAILABLE.wireName ->
                    "这台手机没有可用于此视频的软件读取方式"
                EngineErrorCode.VIDEO_ENCODING_FAILED.wireName -> "当前设置未能完成，可打开 VideoSlim 重试或调整格式和画质"
                EngineErrorCode.CAPTURE_METADATA_FAILED.wireName ->
                    "无法确认原拍摄时间或位置已保留，没有保存不完整结果"
                EngineErrorCode.ENCODER_UNAVAILABLE.wireName -> "当前手机没有可用的视频压缩方式"
                EngineErrorCode.OUTPUT_PERMISSION_LOST.wireName ->
                    "保存文件夹权限已失效，请打开 VideoSlim 重新选择"
                else -> "处理失败，请打开 VideoSlim 查看详情"
            }

        private fun encodingModeText(snapshot: TaskRuntimeSnapshot): String =
            when (snapshot.actualVideoEncodingMode) {
                VideoEncoderMode.EXPLICIT_HARDWARE.wireName -> "硬件编码（系统已确认）"
                VideoEncoderMode.AMBIGUOUS_VENDOR.wireName -> "厂商实现（硬件状态未确认）"
                VideoEncoderMode.SOFTWARE.wireName -> "软件编码"
                else -> "编码方式尚未确认"
            }
    }
}

internal data class ProcessingTerminalNotificationPayload(
    val taskId: String,
    val taskKind: TaskKind,
    val outcome: ActiveTaskTerminalOutcome,
    val source: ActiveTaskFinishSource,
    val terminalSnapshot: TaskRuntimeSnapshot?,
    val errorCode: String?,
    val errorMessage: String?,
) {
    val text: ProcessingNotificationText
        get() = terminalSnapshot?.let(ProcessingNotificationText::from) ?: fallbackText()

    fun asSnapshot(): TaskRuntimeSnapshot =
        terminalSnapshot
            ?: TaskRuntimeSnapshot(
                taskId = taskId,
                percent = if (outcome == ActiveTaskTerminalOutcome.SUCCEEDED) 100.0 else 0.0,
                state = outcome.toRuntimeState(),
                phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                sourceUri = "content://videoslim/terminal/$taskId",
                outputFileName = if (taskKind == TaskKind.AUDIO_EXTRACTION) "audio.m4a" else "video.mp4",
                startedAtEpochMs = 0L,
                taskKind = taskKind,
                outputLocationLabel = "VideoSlim",
                errorCode =
                    if (outcome == ActiveTaskTerminalOutcome.FAILED) {
                        errorCode ?: EngineErrorCode.UNKNOWN.wireName
                    } else {
                        null
                    },
                errorMessage =
                    if (outcome == ActiveTaskTerminalOutcome.FAILED) {
                        errorMessage ?: "媒体处理任务未能完成"
                    } else {
                        null
                    },
            )

    private fun fallbackText(): ProcessingNotificationText {
        val isAudio = taskKind == TaskKind.AUDIO_EXTRACTION
        return when (outcome) {
            ActiveTaskTerminalOutcome.SUCCEEDED ->
                ProcessingNotificationText(
                    title = if (isAudio) "音频提取已完成" else "视频处理已完成",
                    body = "打开 VideoSlim 查看保存结果",
                    progress = 100,
                    ongoing = false,
                    showCancel = false,
                )
            ActiveTaskTerminalOutcome.FAILED ->
                ProcessingNotificationText(
                    title = if (isAudio) "没能完成音频提取" else "没能完成压缩",
                    body = "任务已结束，请打开 VideoSlim 查看详情",
                    progress = 0,
                    ongoing = false,
                    showCancel = false,
                )
            ActiveTaskTerminalOutcome.CANCELLED ->
                ProcessingNotificationText(
                    title = if (isAudio) "音频提取已取消" else "压缩已取消",
                    body = if (isAudio) "原视频和已保存文件没有被修改" else "原视频没有被修改",
                    progress = 0,
                    ongoing = false,
                    showCancel = false,
                )
        }
    }
}

internal class ServiceTerminalNotificationPolicy(
    private val context: ActiveTaskContext,
    private val registrySnapshot: () -> TaskRuntimeSnapshot?,
    private val notifyTerminal: (ProcessingTerminalNotificationPayload) -> Unit,
) {
    fun attempt(terminal: ServiceTerminalDirective) {
        val snapshot = runCatching { registrySnapshot() }.getOrNull()
        notifyTerminal(terminalNotificationPayload(context, terminal, snapshot))
    }
}

internal fun terminalNotificationPayload(
    context: ActiveTaskContext,
    terminal: ServiceTerminalDirective,
    registrySnapshot: TaskRuntimeSnapshot?,
): ProcessingTerminalNotificationPayload {
    val expectedState = terminal.outcome.toRuntimeState()
    val matchingTerminal =
        registrySnapshot?.takeIf { snapshot ->
            snapshot.taskId == context.serviceTaskId &&
                snapshot.taskKind == context.taskKind &&
                snapshot.isTerminal &&
                snapshot.state == expectedState
        }
    return ProcessingTerminalNotificationPayload(
        taskId = context.serviceTaskId,
        taskKind = context.taskKind,
        outcome = terminal.outcome,
        source = terminal.source,
        terminalSnapshot = matchingTerminal,
        errorCode = terminal.errorCode,
        errorMessage = terminal.errorMessage,
    )
}

private fun ActiveTaskTerminalOutcome.toRuntimeState(): String =
    when (this) {
        ActiveTaskTerminalOutcome.SUCCEEDED -> TaskRuntimeSnapshot.STATE_SUCCESS
        ActiveTaskTerminalOutcome.FAILED -> TaskRuntimeSnapshot.STATE_FAILED
        ActiveTaskTerminalOutcome.CANCELLED -> TaskRuntimeSnapshot.STATE_CANCELLED
    }

internal class ProcessingNotificationFactory(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "媒体处理任务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示本机视频压缩或音频提取进度"
                    setShowBadge(false)
                },
            )
        }
    }

    fun foreground(
        snapshot: TaskRuntimeSnapshot,
        cancelIntent: PendingIntent,
    ): Notification {
        require(snapshot.state == TaskRuntimeSnapshot.STATE_RUNNING)
        return build(snapshot, cancelIntent)
    }

    fun terminal(snapshot: TaskRuntimeSnapshot): Notification {
        require(snapshot.isTerminal)
        return build(snapshot, null)
    }

    fun terminal(payload: ProcessingTerminalNotificationPayload): Notification =
        build(
            snapshot = payload.asSnapshot(),
            cancelIntent = null,
            text = payload.text,
            terminalPayload = payload,
        )

    fun notifyForeground(
        snapshot: TaskRuntimeSnapshot,
        cancelIntent: PendingIntent,
    ) {
        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            foreground(snapshot, cancelIntent),
        )
    }

    fun cancelForeground() {
        notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
    }

    fun notifyTerminal(snapshot: TaskRuntimeSnapshot) {
        notificationManager.notify(TERMINAL_NOTIFICATION_ID, terminal(snapshot))
    }

    fun notifyTerminal(payload: ProcessingTerminalNotificationPayload) {
        notificationManager.notify(TERMINAL_NOTIFICATION_ID, terminal(payload))
    }

    private fun build(
        snapshot: TaskRuntimeSnapshot,
        cancelIntent: PendingIntent?,
        text: ProcessingNotificationText = ProcessingNotificationText.from(snapshot),
        terminalPayload: ProcessingTerminalNotificationPayload? = null,
    ): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                appContext,
                CONTENT_REQUEST_CODE,
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    terminalPayload?.let { payload ->
                        putExtra(EXTRA_TERMINAL_TASK_ID, payload.taskId)
                        putExtra(EXTRA_TERMINAL_OUTCOME, payload.outcome.name)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_videoslim)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(text.ongoing)
                .setAutoCancel(!text.ongoing)
                .setProgress(100, text.progress, false)
        if (text.ongoing) {
            builder
                .setWhen(snapshot.startedAtEpochMs)
                .setUsesChronometer(true)
        }
        if (text.showCancel && cancelIntent != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(appContext, R.drawable.ic_stat_videoslim),
                    "取消",
                    cancelIntent,
                ).build(),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && text.ongoing) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "videoslim_processing"
        const val FOREGROUND_NOTIFICATION_ID = 2_001
        const val TERMINAL_NOTIFICATION_ID = 2_002
        private const val CONTENT_REQUEST_CODE = 2_003
        private const val EXTRA_TERMINAL_TASK_ID = "processingTerminalTaskId"
        private const val EXTRA_TERMINAL_OUTCOME = "processingTerminalOutcome"
    }
}
