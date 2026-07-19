package com.videoslim.videoslim

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

internal data class EngineProgressEvent(
    val taskId: String,
    val percent: Double,
    val state: String,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun toChannelMap(): Map<String, Any?> =
        linkedMapOf(
            "taskId" to taskId,
            "percent" to percent,
            "state" to state,
            "outputUri" to outputUri,
            "errorCode" to errorCode,
            "errorMessage" to errorMessage,
        )
}

internal fun interface EngineProgressListener {
    fun onEvent(event: EngineProgressEvent)
}

internal class TranscodeEngine(
    context: Context,
    private val metadataReader: VideoMetadataReader = VideoMetadataReader(context),
    private val mediaStoreSaver: MediaStoreSaver = MediaStoreSaver(context),
    private val logger: (String) -> Unit = {},
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tempDirectory = File(appContext.cacheDir, "transcode")
    private var activeTask: ActiveTask? = null
    @Volatile
    private var disposed = false

    fun getCapabilities(): Map<String, Boolean> =
        mapOf(
            "hevcEncoder" to hasHardwareEncoder(MimeTypes.VIDEO_H265),
            "h264Encoder" to hasHardwareEncoder(MimeTypes.VIDEO_H264),
        )

    fun start(request: ProcessRequest, listener: EngineProgressListener): String {
        requireMainThread()
        check(!disposed) { "Engine has been disposed" }
        if (activeTask != null) {
            throw EngineOperationException(
                EngineFailure(EngineErrorCode.UNKNOWN, "已有视频处理任务正在进行中"),
            )
        }
        if (!hasHardwareEncoder(MimeTypes.VIDEO_H265)) {
            throw EngineOperationException(EngineFailure(EngineErrorCode.ENCODER_UNAVAILABLE))
        }
        if (!tempDirectory.exists() && !tempDirectory.mkdirs()) {
            throw EngineOperationException(
                EngineFailure(EngineErrorCode.UNKNOWN, "无法创建视频处理临时目录"),
            )
        }

        val task =
            ActiveTask(
                id = UUID.randomUUID().toString(),
                request = request,
                tempFile = File(tempDirectory, "${UUID.randomUUID()}.mp4"),
                listener = listener,
            )
        activeTask = task
        emit(task, 0.0, STATE_RUNNING)
        log("task=${task.id} prepare request=$request")
        ioExecutor.execute { prepare(task) }
        return task.id
    }

    fun cancel(taskId: String) {
        requireMainThread()
        val task = activeTask
            ?: throw EngineOperationException(
                EngineFailure(EngineErrorCode.CANCELLED, "任务不存在或已结束"),
            )
        if (task.id != taskId) {
            throw EngineOperationException(
                EngineFailure(EngineErrorCode.CANCELLED, "任务 ID 不匹配或已结束"),
            )
        }
        task.cancelRequested = true
        mainHandler.removeCallbacks(task.progressPoller)
        runCatching { task.transformer?.cancel() }
        if (task.stage != Stage.PUBLISHING) {
            finishCancelled(task)
        }
    }

    fun dispose() {
        requireMainThread()
        if (disposed) return
        disposed = true
        activeTask?.let { task ->
            task.cancelRequested = true
            mainHandler.removeCallbacks(task.progressPoller)
            runCatching { task.transformer?.cancel() }
            runCatching { task.tempFile.delete() }
        }
        activeTask = null
        ioExecutor.shutdownNow()
    }

    private fun prepare(task: ActiveTask) {
        try {
            if (task.cancelRequested) return
            val metadata = metadataReader.read(task.request.sourceUri)
            if (metadata.isHdr) {
                throw EngineOperationException(
                    EngineFailure(EngineErrorCode.UNKNOWN, "M1 暂不支持 HDR 视频，请选择 SDR 视频"),
                )
            }
            metadata.audioMime?.let { audioMime ->
                val supportedAudio =
                    DefaultMuxer.Factory().getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)
                if (audioMime !in supportedAudio) {
                    throw EngineOperationException(
                        EngineFailure(
                            EngineErrorCode.UNKNOWN,
                            "源视频音频格式无法原样复制到 MP4，M1 已停止处理",
                        ),
                    )
                }
            }
            ensureStorage(metadata, task.request.videoBitrate)
            postToMain {
                if (!isCurrent(task)) return@postToMain
                if (task.cancelRequested) {
                    finishCancelled(task)
                } else {
                    beginTransformer(task)
                }
            }
        } catch (error: Throwable) {
            postToMain { fail(task, mapPreparationFailure(error), error) }
        }
    }

    private fun beginTransformer(task: ActiveTask) {
        requireMainThread()
        try {
            task.stage = Stage.TRANSFORMING
            val settings =
                VideoEncoderSettings.Builder()
                    .setBitrate(task.request.videoBitrate)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            val encoderFactory =
                DefaultEncoderFactory.Builder(appContext)
                    .setRequestedVideoEncoderSettings(settings)
                    .setEnableFallback(false)
                    .build()
            val transformer =
                Transformer.Builder(appContext)
                    .setEncoderFactory(encoderFactory)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                onTransformerCompleted(task)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                val failure =
                                    EngineErrorMapper.fromExportErrorCode(exportException.errorCode)
                                fail(task, failure, exportException)
                            }
                        },
                    ).build()
            task.transformer = transformer
            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(task.request.sourceUri)).build()
            transformer.start(editedItem, task.tempFile.absolutePath)
            scheduleProgress(task)
            log("task=${task.id} transformer started temp=${task.tempFile.name}")
        } catch (error: Throwable) {
            fail(task, EngineErrorMapper.fromThrowable(error), error)
        }
    }

    private fun scheduleProgress(task: ActiveTask) {
        if (!isCurrent(task) || task.stage != Stage.TRANSFORMING) return
        val transformer = task.transformer ?: return
        try {
            val holder = ProgressHolder()
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val monotonic = max(task.lastPercent, holder.progress.coerceIn(0, 99).toDouble())
                if (monotonic > task.lastPercent) {
                    emit(task, monotonic, STATE_RUNNING)
                }
            }
        } catch (error: Throwable) {
            log("task=${task.id} progress query failed ${error.stackTraceToString()}")
        }
        if (isCurrent(task) && task.stage == Stage.TRANSFORMING) {
            mainHandler.postDelayed(task.progressPoller, PROGRESS_INTERVAL_MS)
        }
    }

    private fun onTransformerCompleted(task: ActiveTask) {
        if (!isCurrent(task)) {
            runCatching { task.tempFile.delete() }
            return
        }
        mainHandler.removeCallbacks(task.progressPoller)
        if (task.cancelRequested) {
            finishCancelled(task)
            return
        }
        task.stage = Stage.PUBLISHING
        emit(task, max(task.lastPercent, PUBLISHING_PERCENT), STATE_RUNNING)
        ioExecutor.execute {
            var outputUri: String? = null
            try {
                outputUri = mediaStoreSaver.publishVideo(task.tempFile, task.request.outputFileName)
                val publishedUri = outputUri
                if (task.cancelRequested || disposed) {
                    mediaStoreSaver.deletePublished(publishedUri)
                    mainHandler.post {
                        runCatching { task.tempFile.delete() }
                        if (!disposed && isCurrent(task)) finishCancelled(task)
                    }
                } else {
                    // Always post this final cleanup, even if dispose() happens after the I/O
                    // check. The main-thread callback re-checks cancellation/disposal atomically
                    // with terminal state publication.
                    mainHandler.post {
                        if (disposed || !isCurrent(task) || task.cancelRequested) {
                            mediaStoreSaver.deletePublished(publishedUri)
                            runCatching { task.tempFile.delete() }
                            if (!disposed && isCurrent(task)) finishCancelled(task)
                            return@post
                        }
                        runCatching { task.tempFile.delete() }
                        task.stage = Stage.FINISHED
                        activeTask = null
                        emit(task, 100.0, STATE_SUCCESS, outputUri = publishedUri)
                        log("task=${task.id} success output=$publishedUri")
                    }
                }
            } catch (error: Throwable) {
                outputUri?.let(mediaStoreSaver::deletePublished)
                postToMain { fail(task, EngineErrorMapper.fromThrowable(error), error) }
            }
        }
    }

    private fun fail(task: ActiveTask, failure: EngineFailure, error: Throwable) {
        requireMainThread()
        if (!isCurrent(task)) {
            runCatching { task.tempFile.delete() }
            return
        }
        if (task.cancelRequested || failure.code == EngineErrorCode.CANCELLED) {
            finishCancelled(task)
            return
        }
        mainHandler.removeCallbacks(task.progressPoller)
        runCatching { task.transformer?.cancel() }
        runCatching { task.tempFile.delete() }
        task.stage = Stage.FINISHED
        activeTask = null
        emit(
            task,
            task.lastPercent,
            STATE_FAILED,
            failure = failure,
        )
        log(
            "task=${task.id} failed code=${failure.code.wireName} " +
                "message=${failure.message} stack=${error.stackTraceToString()}",
        )
    }

    private fun finishCancelled(task: ActiveTask) {
        requireMainThread()
        if (!isCurrent(task)) return
        mainHandler.removeCallbacks(task.progressPoller)
        runCatching { task.transformer?.cancel() }
        runCatching { task.tempFile.delete() }
        task.stage = Stage.FINISHED
        activeTask = null
        emit(
            task,
            task.lastPercent,
            STATE_CANCELLED,
            failure = EngineFailure(EngineErrorCode.CANCELLED),
        )
        log("task=${task.id} cancelled")
    }

    private fun emit(
        task: ActiveTask,
        percent: Double,
        state: String,
        outputUri: String? = null,
        failure: EngineFailure? = null,
    ) {
        val monotonic = max(task.lastPercent, percent.coerceIn(0.0, 100.0))
        if (state == STATE_RUNNING) {
            val now = SystemClock.elapsedRealtime()
            if (
                task.lastRunningEventAtMs != NO_RUNNING_EVENT &&
                now - task.lastRunningEventAtMs < PROGRESS_INTERVAL_MS
            ) {
                return
            }
            task.lastRunningEventAtMs = now
        }
        task.lastPercent = monotonic
        runCatching {
            task.listener.onEvent(
                EngineProgressEvent(
                    taskId = task.id,
                    percent = monotonic,
                    state = state,
                    outputUri = outputUri,
                    errorCode = failure?.code?.wireName,
                    errorMessage = failure?.message,
                ),
            )
        }.onFailure { error -> log("task=${task.id} event callback failed ${error.stackTraceToString()}") }
    }

    private fun ensureStorage(metadata: VideoMetadata, bitrate: Int) {
        val videoBytes = safeMultiply(metadata.durationMs, bitrate.toLong()) / BITS_PER_MILLISECOND
        val overhead = max(MIN_OVERHEAD_BYTES, metadata.fileSizeBytes / SOURCE_OVERHEAD_DIVISOR)
        val oneOutput = safeAdd(videoBytes, overhead)
        val cacheRequired = safeAdd(safeMultiply(oneOutput, 2L), STORAGE_HEADROOM_BYTES)
        val publicRequired = safeAdd(oneOutput, STORAGE_HEADROOM_BYTES)
        val cacheAvailable = StatFs(appContext.cacheDir.absolutePath).availableBytes
        val publicAvailable = StatFs(publicStorageRoot().absolutePath).availableBytes
        if (cacheAvailable < cacheRequired || publicAvailable < publicRequired) {
            throw EngineOperationException(
                EngineFailure(
                    EngineErrorCode.INSUFFICIENT_STORAGE,
                    "存储空间不足：应用缓存需 ${cacheRequired / MEBIBYTE} MiB，" +
                        "系统影片目录需 ${publicRequired / MEBIBYTE} MiB 可用空间",
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun publicStorageRoot(): File {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        return if (movies.exists()) movies else Environment.getExternalStorageDirectory()
    }

    private fun mapPreparationFailure(error: Throwable): EngineFailure =
        when (error) {
            is EngineOperationException -> error.failure
            is VideoMetadataException ->
                if (error.code == VideoMetadataException.SOURCE_CORRUPTED) {
                    EngineFailure(EngineErrorCode.SOURCE_CORRUPTED, error.message)
                } else {
                    EngineFailure(EngineErrorCode.UNKNOWN, error.message)
                }
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun hasHardwareEncoder(mimeType: String): Boolean =
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    isHardwareCodec(info)
            }
        }.getOrElse { error ->
            log("capability detection failed ${error.stackTraceToString()}")
            false
        }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.contains("software") &&
            !name.contains("ffmpeg") &&
            !name.endsWith(".sw")
    }

    private fun isCurrent(task: ActiveTask): Boolean = !disposed && activeTask === task

    private fun postToMain(action: () -> Unit) {
        if (!disposed) mainHandler.post { if (!disposed) action() }
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TranscodeEngine must be called on the main looper"
        }
    }

    private fun safeMultiply(left: Long, right: Long): Long =
        if (left <= 0L || right <= 0L) 0L
        else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE
        else left * right

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private inner class ActiveTask(
        val id: String,
        val request: ProcessRequest,
        val tempFile: File,
        val listener: EngineProgressListener,
    ) {
        @Volatile var cancelRequested: Boolean = false
        var transformer: Transformer? = null
        var stage: Stage = Stage.PREPARING
        var lastPercent: Double = 0.0
        var lastRunningEventAtMs: Long = NO_RUNNING_EVENT
        val progressPoller = Runnable { scheduleProgress(this) }
    }

    private enum class Stage { PREPARING, TRANSFORMING, PUBLISHING, FINISHED }

    private companion object {
        const val STATE_RUNNING = "running"
        const val STATE_SUCCESS = "success"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
        const val PROGRESS_INTERVAL_MS = 500L
        const val NO_RUNNING_EVENT = -1L
        const val PUBLISHING_PERCENT = 99.0
        const val BITS_PER_MILLISECOND = 8_000L
        const val SOURCE_OVERHEAD_DIVISOR = 20L
        const val MIN_OVERHEAD_BYTES = 16L * 1024L * 1024L
        const val STORAGE_HEADROOM_BYTES = 32L * 1024L * 1024L
        const val MEBIBYTE = 1024L * 1024L
    }
}
