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
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
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
    private val recoveryStore: TaskRecoveryStore = TaskRecoveryStore(context),
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
        val requestedVideoMime = videoMimeType(request.videoCodec)
        if (!hasHardwareEncoder(requestedVideoMime)) {
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
        try {
            recoveryStore.begin(
                taskId = task.id,
                tempFileName = task.tempFile.name,
                expectedOutputDisplayName = request.outputFileName,
            )
            task.recoveryStarted = true
        } catch (error: Throwable) {
            runCatching { task.tempFile.delete() }
            throw EngineOperationException(
                EngineFailure(EngineErrorCode.UNKNOWN, "无法建立任务恢复日志，请重试"),
                error,
            )
        }
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
            val plan = TranscodePlan.create(task.request, metadata, Build.VERSION.SDK_INT)
            if (task.request.audioMode == AudioMode.COPY) {
                metadata.audioMime?.let { audioMime ->
                    val supportedAudio =
                        DefaultMuxer.Factory().getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)
                    if (audioMime !in supportedAudio) {
                        throw EngineOperationException(
                            EngineFailure(
                                EngineErrorCode.UNKNOWN,
                                "源视频音频格式无法原样复制到 MP4，请选择 AAC 重编码或移除音轨",
                            ),
                        )
                    }
                }
            }
            ensureStorage(plan.storageEstimate)
            task.plan = plan
            log("task=${task.id} plan=$plan")
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
            recoveryStore.updateStage(task.id, RecoveryStage.TRANSFORMING)
            task.stage = Stage.TRANSFORMING
            val plan = checkNotNull(task.plan) { "Transcode plan is missing" }
            val settings =
                VideoEncoderSettings.Builder()
                    .setBitrate(task.request.videoBitrate)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            val encoderFactoryBuilder =
                DefaultEncoderFactory.Builder(appContext)
                    .setRequestedVideoEncoderSettings(settings)
                    .setEnableFallback(false)
            if (task.request.audioMode == AudioMode.REENCODE) {
                encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(checkNotNull(task.request.audioBitrate))
                        .build(),
                )
            }
            val transformerBuilder =
                Transformer.Builder(appContext)
                    .setEncoderFactory(encoderFactoryBuilder.build())
                    .setVideoMimeType(videoMimeType(task.request.videoCodec))
            if (task.request.audioMode == AudioMode.REENCODE) {
                transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }
            val transformer =
                transformerBuilder
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
                                    EngineErrorMapper.fromExportErrorCode(
                                        errorCode = exportException.errorCode,
                                        wasHdrToneMapping =
                                            plan.hdrMode ==
                                                HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                                    )
                                fail(task, failure, exportException)
                            }
                        },
                    ).build()
            task.transformer = transformer

            val editedItemBuilder =
                EditedMediaItem.Builder(MediaItem.fromUri(task.request.sourceUri))
                    .setRemoveAudio(task.request.audioMode == AudioMode.REMOVE)
            if (plan.presentationRequired) {
                editedItemBuilder.setEffects(
                    Effects(
                        emptyList(),
                        listOf(
                            Presentation.createForWidthAndHeight(
                                plan.outputDimensions.width,
                                plan.outputDimensions.height,
                                Presentation.LAYOUT_SCALE_TO_FIT,
                            ),
                        ),
                    ),
                )
            }
            val sequence = EditedMediaItemSequence.Builder(editedItemBuilder.build()).build()
            val composition =
                Composition.Builder(sequence)
                    .setHdrMode(
                        if (plan.hdrMode == HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) {
                            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                        } else {
                            Composition.HDR_MODE_KEEP_HDR
                        },
                    ).build()
            transformer.start(composition, task.tempFile.absolutePath)
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
            try {
                val publishedUri =
                    mediaStoreSaver.publishVideo(
                        task.tempFile,
                        task.request.outputFileName,
                    ) {
                        task.cancelRequested || disposed || Thread.currentThread().isInterrupted
                    }
                if (task.cancelRequested || disposed) {
                    discardPublished(task, publishedUri)
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
                            discardPublished(task, publishedUri)
                            runCatching { task.tempFile.delete() }
                            if (!disposed && isCurrent(task)) finishCancelled(task)
                            return@post
                        }
                        cleanupTempAndRecovery(task)
                        task.stage = Stage.FINISHED
                        activeTask = null
                        emit(task, 100.0, STATE_SUCCESS, outputUri = publishedUri)
                        log("task=${task.id} success output=$publishedUri")
                    }
                }
            } catch (error: Throwable) {
                if (error is PublicationCleanupException && !error.cleanupConfirmed) {
                    task.retainRecovery = true
                    log("task=${task.id} publication cleanup unconfirmed; recovery retained")
                }
                if (task.cancelRequested || disposed) {
                    postToMain {
                        if (isCurrent(task)) finishCancelled(task)
                    }
                } else {
                    postToMain { fail(task, EngineErrorMapper.fromThrowable(error), error) }
                }
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
        cleanupTempAndRecovery(task)
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
        cleanupTempAndRecovery(task)
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

    private fun ensureStorage(estimate: StorageEstimate) {
        val cacheAvailable = StatFs(appContext.cacheDir.absolutePath).availableBytes
        val publicAvailable = StatFs(publicStorageRoot().absolutePath).availableBytes
        if (
            cacheAvailable < estimate.cacheRequiredBytes ||
            publicAvailable < estimate.publicRequiredBytes
        ) {
            throw EngineOperationException(
                EngineFailure(
                    EngineErrorCode.INSUFFICIENT_STORAGE,
                    "存储空间不足：应用缓存需 ${estimate.cacheRequiredBytes / MEBIBYTE} MiB，" +
                        "系统影片目录需 ${estimate.publicRequiredBytes / MEBIBYTE} MiB 可用空间",
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
            is TranscodePlanException -> error.failure
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

    private fun videoMimeType(codec: VideoCodec): String =
        when (codec) {
            VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            VideoCodec.H264 -> MimeTypes.VIDEO_H264
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

    private fun discardPublished(task: ActiveTask, outputUri: String) {
        val boundaryRecorded =
            runCatching { recoveryStore.markDiscarding(task.id) }
                .onFailure { error ->
                    log("task=${task.id} discard boundary failed ${error.stackTraceToString()}")
                }.isSuccess
        val cleanupConfirmed = mediaStoreSaver.deletePublished(outputUri)
        if (!boundaryRecorded || !cleanupConfirmed) {
            task.retainRecovery = true
            log(
                "task=${task.id} published output cleanup unconfirmed " +
                    "boundary=$boundaryRecorded deleted=$cleanupConfirmed",
            )
        }
    }

    private fun cleanupTempAndRecovery(task: ActiveTask) {
        val tempRemoved =
            runCatching {
                !task.tempFile.exists() || task.tempFile.delete() || !task.tempFile.exists()
            }.getOrElse { error ->
                log("task=${task.id} temp cleanup failed ${error.stackTraceToString()}")
                false
            }
        if (!tempRemoved) {
            log("task=${task.id} temp cleanup returned false; recovery journal retained")
            return
        }
        if (task.retainRecovery) {
            log("task=${task.id} recovery journal retained for public output reconciliation")
            return
        }
        if (task.recoveryStarted) {
            runCatching { recoveryStore.clear(task.id) }
                .onSuccess { task.recoveryStarted = false }
                .onFailure { error ->
                    log("task=${task.id} recovery clear failed ${error.stackTraceToString()}")
                }
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TranscodeEngine must be called on the main looper"
        }
    }

    private inner class ActiveTask(
        val id: String,
        val request: ProcessRequest,
        val tempFile: File,
        val listener: EngineProgressListener,
    ) {
        @Volatile var cancelRequested: Boolean = false
        @Volatile var recoveryStarted: Boolean = false
        @Volatile var retainRecovery: Boolean = false
        var plan: TranscodePlan? = null
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
        const val MEBIBYTE = 1024L * 1024L
    }
}
