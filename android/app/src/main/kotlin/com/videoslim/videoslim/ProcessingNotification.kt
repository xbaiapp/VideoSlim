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
                TaskRuntimeSnapshot.STATE_RUNNING ->
                    ProcessingNotificationText(
                        title = "正在压缩视频",
                        body = "已完成 $progress%",
                        progress = progress,
                        ongoing = true,
                        showCancel = true,
                    )

                TaskRuntimeSnapshot.STATE_SUCCESS ->
                    ProcessingNotificationText(
                        title = "压缩完成",
                        body = "已保存到 Movies/VideoSlim/${snapshot.outputFileName}",
                        progress = 100,
                        ongoing = false,
                        showCancel = false,
                    )

                TaskRuntimeSnapshot.STATE_FAILED ->
                    ProcessingNotificationText(
                        title = "压缩失败",
                        body = snapshot.errorMessage ?: "处理失败，请打开 VideoSlim 查看详情",
                        progress = progress,
                        ongoing = false,
                        showCancel = false,
                    )

                TaskRuntimeSnapshot.STATE_CANCELLED ->
                    ProcessingNotificationText(
                        title = "任务已取消",
                        body = "没有生成输出文件",
                        progress = progress,
                        ongoing = false,
                        showCancel = false,
                    )

                else -> throw IllegalArgumentException("Unknown task state: ${snapshot.state}")
            }
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
