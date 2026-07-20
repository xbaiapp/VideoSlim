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
            return when (snapshot.state) {
                TaskRuntimeSnapshot.STATE_RUNNING -> running(snapshot, progress)

                TaskRuntimeSnapshot.STATE_SUCCESS ->
                    ProcessingNotificationText(
                        title = "视频已压缩并保存",
                        body = "已保存到 ${snapshot.outputLocationLabel} · ${encodingModeText(snapshot)}",
                        progress = 100,
                        ongoing = false,
                        showCancel = false,
                    )

                TaskRuntimeSnapshot.STATE_FAILED ->
                    ProcessingNotificationText(
                        title = "没能完成压缩",
                        body = "${failureBody(snapshot)} · ${encodingModeText(snapshot)}",
                        progress = progress,
                        ongoing = false,
                        showCancel = false,
                    )

                TaskRuntimeSnapshot.STATE_CANCELLED ->
                    ProcessingNotificationText(
                        title = "压缩已取消",
                        body = "原视频没有被修改",
                        progress = progress,
                        ongoing = false,
                        showCancel = false,
                    )

                else -> throw IllegalArgumentException("Unknown task state: ${snapshot.state}")
            }
        }

        private fun running(
            snapshot: TaskRuntimeSnapshot,
            progress: Int,
        ): ProcessingNotificationText =
            when (snapshot.phase) {
                TaskRuntimeSnapshot.PHASE_PREPARING ->
                    ProcessingNotificationText(
                        title = "正在准备视频",
                        body = "即将开始压缩 · ${encodingModeText(snapshot)}",
                        progress = progress,
                        ongoing = true,
                        showCancel = true,
                    )
                TaskRuntimeSnapshot.PHASE_ENCODING ->
                    ProcessingNotificationText(
                        title = "正在压缩视频",
                        body = "已完成 $progress% · ${encodingModeText(snapshot)}",
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

        private fun failureBody(snapshot: TaskRuntimeSnapshot): String =
            when (snapshot.errorCode) {
                EngineErrorCode.INSUFFICIENT_STORAGE.wireName -> "存储空间不足，请释放空间后重试"
                EngineErrorCode.SOURCE_PERMISSION_LOST.wireName -> "无法继续读取视频，请打开 VideoSlim 重新选择"
                EngineErrorCode.SOURCE_UNAVAILABLE.wireName -> "所选视频已移动、删除或暂时不可用"
                EngineErrorCode.SOURCE_PROVIDER_FAILED.wireName -> "手机无法持续读取视频，请重新选择或稍后重试"
                EngineErrorCode.SOURCE_CORRUPTED.wireName -> "无法处理这个视频，文件可能损坏或格式不受支持"
                EngineErrorCode.VIDEO_DECODING_FAILED.wireName ->
                    if (snapshot.videoDecoderMode == VideoDecoderMode.HARDWARE.wireName) {
                        "视频读取方式未能完成，原视频没有被修改。可打开 VideoSlim 使用兼容模式重试"
                    } else {
                        "软件读取方式未能完成，原视频没有被修改。可打开 VideoSlim 查看详情"
                    }
                EngineErrorCode.VIDEO_FORMAT_UNSUPPORTED.wireName -> "这台手机暂时无法读取这种视频格式"
                EngineErrorCode.COMPATIBILITY_DECODER_UNAVAILABLE.wireName ->
                    "这台手机没有可用于此视频的软件读取方式"
                EngineErrorCode.VIDEO_ENCODING_FAILED.wireName -> "当前设置未能完成，可打开 VideoSlim 重试或调整格式和画质"
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

internal class ProcessingNotificationFactory(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "视频压缩任务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示本机视频压缩进度"
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

    fun notifyForeground(
        snapshot: TaskRuntimeSnapshot,
        cancelIntent: PendingIntent,
    ) {
        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            foreground(snapshot, cancelIntent),
        )
    }

    fun notifyTerminal(snapshot: TaskRuntimeSnapshot) {
        notificationManager.notify(TERMINAL_NOTIFICATION_ID, terminal(snapshot))
    }

    private fun build(
        snapshot: TaskRuntimeSnapshot,
        cancelIntent: PendingIntent?,
    ): Notification {
        val text = ProcessingNotificationText.from(snapshot)
        val contentIntent =
            PendingIntent.getActivity(
                appContext,
                CONTENT_REQUEST_CODE,
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
    }
}
