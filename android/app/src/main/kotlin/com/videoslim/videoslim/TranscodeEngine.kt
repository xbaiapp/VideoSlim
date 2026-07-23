package com.videoslim.videoslim

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.Clock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.InAppMp4Muxer
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
    val phase: String,
    val actualVideoEncodingMode: VideoEncoderMode = VideoEncoderMode.UNKNOWN,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun toChannelMap(): Map<String, Any?> =
        linkedMapOf(
            "taskId" to taskId,
            "percent" to percent,
            "state" to state,
            "phase" to phase,
            "actualVideoEncodingMode" to actualVideoEncodingMode.wireName,
            "outputUri" to outputUri,
            "errorCode" to errorCode,
            "errorMessage" to errorMessage,
        )
}

internal fun interface EngineProgressListener {
    fun onEvent(event: EngineProgressEvent)
}

internal class PublicationBoundary {
    @Volatile
    private var pending = false

    fun begin() {
        pending = true
    }

    fun complete() {
        pending = false
    }

    fun shouldDeferCancellation(): Boolean = pending
}

internal class TranscodeEngine(
    context: Context,
    private val metadataReader: VideoMetadataReader = VideoMetadataReader(context),
    private val mediaStoreSaver: MediaStoreSaver = MediaStoreSaver(context),
    private val recoveryStore: TaskRecoveryStore = TaskRecoveryStore(context),
    private val sourceAccessProbe: SourceAccessProbe = SourceAccessProbe(context),
    private val codecCatalog: HardwareCodecCatalog = HardwareCodecCatalog(),
    private val captureMetadataVerifier: CaptureMetadataFileVerifier = CaptureMetadataFileVerifier(),
    private val logger: (String) -> Unit = {},
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tempDirectory = File(appContext.cacheDir, "transcode")
    private var activeTask: ActiveTask? = null
    @Volatile
    private var disposed = false

    fun getCapabilities(): Map<String, Boolean> {
        log(
            "codec capability inventory " +
                "hevcEncoders=${codecCatalog.candidateSummary(MimeTypes.VIDEO_H265, encoder = true)} " +
                "h264Encoders=${codecCatalog.candidateSummary(MimeTypes.VIDEO_H264, encoder = true)}",
        )
        return mapOf(
            "hevcEncoder" to codecCatalog.hasHardwareEncoder(MimeTypes.VIDEO_H265),
            "h264Encoder" to codecCatalog.hasHardwareEncoder(MimeTypes.VIDEO_H264),
        )
    }

    fun validateOutputDestination(outputTreeUri: String?) {
        try {
            mediaStoreSaver.validateOutputDestination(outputTreeUri)
        } catch (error: OutputPermissionException) {
            throw EngineOperationException(EngineFailure(EngineErrorCode.OUTPUT_PERMISSION_LOST), error)
        }
    }

    fun start(request: ProcessRequest, listener: EngineProgressListener): String {
        requireMainThread()
        check(!disposed) { "Engine has been disposed" }
        if (activeTask != null) {
            throw EngineOperationException(
                EngineFailure(EngineErrorCode.UNKNOWN, "已有视频处理任务正在进行中"),
            )
        }
        val requestedVideoMime = videoMimeType(request.videoCodec)
        if (!codecCatalog.hasHardwareEncoder(requestedVideoMime)) {
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
        if (task.cancelRequested) return
        val deferForPublication = task.publicationBoundary.shouldDeferCancellation()
        task.cancelRequested = true
        task.stage = Stage.CANCELLING
        emit(task, task.lastPercent, STATE_RUNNING)
        mainHandler.removeCallbacks(task.progressPoller)
        runCatching { task.transformer?.cancel() }
        if (!deferForPublication) {
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
            lateinit var sourceAccess: SourceAccessProbeResult
            EngineIoPreparationPolicy.prepare(
                validateDestination = {
                    validateOutputDestination(task.request.outputTreeUri)
                },
                prepareMedia = mediaPreparation@{
                    if (task.cancelRequested) return@mediaPreparation
                    log(
                        "task=${task.id} environment manufacturer=${Build.MANUFACTURER} " +
                            "model=${Build.MODEL} device=${Build.DEVICE} sdk=${Build.VERSION.SDK_INT} " +
                            "release=${Build.VERSION.RELEASE} securityPatch=${Build.VERSION.SECURITY_PATCH} " +
                            "app=${appVersionName()} media3=${MediaLibraryInfo.VERSION}",
                    )
                    sourceAccess = sourceAccessProbe.probe(task.request.sourceUri)
                },
            )
            if (task.cancelRequested) return
            task.sourceAccessAtStart = sourceAccess
            log("task=${task.id} source access before metadata ${sourceAccess.toLogString()}")
            sourceAccess.toEngineFailure()?.let { failure ->
                throw EngineOperationException(failure)
            }
            val metadata = metadataReader.read(task.request.sourceUri)
            task.captureMetadataPolicy = CaptureMetadataPolicy(metadata.captureMetadata)
            log(
                "task=${task.id} source capture metadata " +
                    "captureTimePresent=${metadata.captureMetadata.hasCaptureTime} " +
                    "locationPresent=${metadata.captureMetadata.hasLocation}",
            )
            if (
                sourceAccess.statSize != null &&
                metadata.fileSizeBytes > 0L &&
                sourceAccess.statSize != metadata.fileSizeBytes
            ) {
                throw EngineOperationException(
                    EngineFailure(
                        EngineErrorCode.SOURCE_UNAVAILABLE,
                        "所选视频在处理前发生了变化，请重新选择文件",
                    ),
                )
            }
            val plan = TranscodePlan.create(task.request, metadata, Build.VERSION.SDK_INT)
            val outputMime = videoMimeType(task.request.videoCodec)
            val compatibleDecoders =
                when (task.request.videoDecoderMode) {
                    VideoDecoderMode.HARDWARE ->
                        codecCatalog.compatibleVideoDecoderInfos(
                            mimeType = metadata.videoMime,
                            width = metadata.storageWidth,
                            height = metadata.storageHeight,
                            frameRate = metadata.frameRate,
                            profile = metadata.videoProfile,
                            level = metadata.videoLevel,
                        )
                    VideoDecoderMode.SOFTWARE ->
                        codecCatalog.compatibleSoftwareVideoDecoderInfos(
                            mimeType = metadata.videoMime,
                            width = metadata.storageWidth,
                            height = metadata.storageHeight,
                            frameRate = metadata.frameRate,
                            profile = metadata.videoProfile,
                            level = metadata.videoLevel,
                        )
                }
            val compatibleEncoders =
                codecCatalog.compatibleVideoEncoderInfos(
                    mimeType = outputMime,
                    width = plan.outputDimensions.width,
                    height = plan.outputDimensions.height,
                    frameRate = metadata.frameRate,
                    bitrate = task.request.videoBitrate,
                )
            log(
                "task=${task.id} decoder inventory " +
                    codecCatalog.candidateSummary(metadata.videoMime, encoder = false),
            )
            log(
                "task=${task.id} encoder inventory " +
                    codecCatalog.candidateSummary(outputMime, encoder = true),
            )
            log(
                "task=${task.id} compatible ${task.request.videoDecoderMode.wireName} decoder=" +
                    compatibleDecoders.joinToString { it.name } +
                    " input=${metadata.storageWidth}x${metadata.storageHeight}@${metadata.frameRate}" +
                    " profile=${metadata.videoProfile} level=${metadata.videoLevel}" +
                    " encoder=${compatibleEncoders.joinToString { it.name }}",
            )
            if (compatibleDecoders.isEmpty()) {
                throw EngineOperationException(
                    EngineFailure(
                        if (task.request.videoDecoderMode == VideoDecoderMode.SOFTWARE) {
                            EngineErrorCode.COMPATIBILITY_DECODER_UNAVAILABLE
                        } else {
                            EngineErrorCode.VIDEO_FORMAT_UNSUPPORTED
                        },
                    ),
                )
            }
            if (compatibleEncoders.isEmpty()) {
                throw EngineOperationException(EngineFailure(EngineErrorCode.ENCODER_UNAVAILABLE))
            }
            task.compatibleDecoderNames = compatibleDecoders.mapTo(linkedSetOf()) { it.name }
            task.compatibleEncoderNames = compatibleEncoders.mapTo(linkedSetOf()) { it.name }
            if (task.request.audioMode == AudioMode.COPY) {
                metadata.audioMime?.let { audioMime ->
                    val supportedAudio =
                        InAppMp4Muxer.Factory().getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)
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
            ensureStorage(
                plan.storageEstimate,
                requiresPublicDestination = task.request.outputTreeUri == null,
            )
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
            val captureMetadataPolicy =
                checkNotNull(task.captureMetadataPolicy) { "Capture metadata policy is missing" }
            val muxerFactory = InAppMp4Muxer.Factory(captureMetadataPolicy)
            val settings =
                VideoEncoderSettings.Builder()
                    .setBitrate(task.request.videoBitrate)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            val encoderFactoryBuilder =
                DefaultEncoderFactory.Builder(appContext)
                    .setVideoEncoderSelector(
                        HardwareVideoEncoderSelector(
                            catalog = codecCatalog,
                            allowedCodecNames = task.compatibleEncoderNames,
                            logger = ::log,
                        ),
                    )
                    .setRequestedVideoEncoderSettings(settings)
                    .setEnableFallback(true)
            if (task.request.audioMode == AudioMode.REENCODE) {
                encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(checkNotNull(task.request.audioBitrate))
                        .build(),
                )
            }
            val encoderFactory =
                LoggingEncoderFactory(
                    delegate = encoderFactoryBuilder.build(),
                    logger = ::log,
                    onVideoEncoderCreated = { codecName ->
                        postToMain { recordActualVideoEncoder(task, codecName) }
                    },
                    forceAudioEncoding = shouldForceAudioEncoding(task.request.audioMode),
                )
            val decoderFactory =
                DefaultDecoderFactory.Builder(appContext)
                    .setMediaCodecSelector(
                        ModeVideoDecoderSelector(
                            allowedCodecNames = task.compatibleDecoderNames,
                            mode = task.request.videoDecoderMode,
                            logger = ::log,
                        ),
                    )
                    .setEnableDecoderFallback(true)
                    .setListener { codecName, initializationFailures ->
                        log(
                            "actual decoder name=$codecName " +
                                "priorInitializationFailures=${initializationFailures.size}",
                        )
                    }.build()
            val assetLoaderFactory =
                DefaultAssetLoaderFactory(
                    appContext,
                    decoderFactory,
                    Clock.DEFAULT,
                    DefaultMediaSourceFactory(appContext),
                    DataSourceBitmapLoader(appContext),
                )
            val transformerBuilder =
                Transformer.Builder(appContext)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setEncoderFactory(encoderFactory)
                    .setMuxerFactory(muxerFactory)
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
                                handleTransformerError(
                                    task = task,
                                    exportException = exportException,
                                    wasHdrToneMapping =
                                        plan.hdrMode ==
                                            HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                                )
                            }
                        },
                    ).build()
            task.transformer = transformer

            val editedItemBuilder =
                EditedMediaItem.Builder(MediaItem.fromUri(task.request.sourceUri))
                    .setRemoveAudio(task.request.audioMode == AudioMode.REMOVE)
            val videoEffects = mutableListOf<Effect>()
            plan.videoEffectOrder.forEach { effect ->
                when (effect) {
                    VideoEffectKind.CROP -> {
                        val crop = checkNotNull(plan.crop).ndc
                        videoEffects += Crop(crop.left, crop.right, crop.bottom, crop.top)
                    }
                    VideoEffectKind.PRESENTATION -> {
                        videoEffects +=
                            Presentation.createForWidthAndHeight(
                                plan.outputDimensions.width,
                                plan.outputDimensions.height,
                                Presentation.LAYOUT_SCALE_TO_FIT,
                            )
                    }
                }
            }
            if (videoEffects.isNotEmpty()) {
                // Order is a product invariant: crop display pixels before any scaling.
                editedItemBuilder.setEffects(Effects(emptyList(), videoEffects))
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
            emit(task, task.lastPercent, STATE_RUNNING)
            scheduleProgress(task)
            log("task=${task.id} transformer started temp=${task.tempFile.name}")
        } catch (error: Throwable) {
            val exportException = error.exportExceptionCause()
            if (exportException != null) {
                handleTransformerError(
                    task = task,
                    exportException = exportException,
                    wasHdrToneMapping =
                        task.plan?.hdrMode == HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                )
            } else {
                fail(task, EngineErrorMapper.fromThrowable(error), error)
            }
        }
    }

    private fun Throwable.exportExceptionCause(): ExportException? {
        var current: Throwable? = this
        repeat(MAX_CAUSE_DEPTH) {
            val candidate = current ?: return null
            if (candidate is ExportException) return candidate
            val next = candidate.cause
            if (next === candidate) return null
            current = next
        }
        return null
    }

    private fun recordActualVideoEncoder(
        task: ActiveTask,
        codecName: String,
    ) {
        if (!isCurrent(task)) return
        val mode = codecCatalog.videoEncoderMode(codecName)
        if (task.actualVideoEncodingMode == VideoEncoderMode.UNKNOWN) {
            task.actualVideoEncodingMode = mode
            emit(task, task.lastPercent, STATE_RUNNING)
        } else if (task.actualVideoEncodingMode != mode) {
            log(
                "task=${task.id} conflicting actual video encoder mode " +
                    "kept=${task.actualVideoEncodingMode.wireName} ignored=${mode.wireName}",
            )
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
        task.transformer = null
        if (task.cancelRequested) {
            finishCancelled(task)
            return
        }
        task.publicationBoundary.begin()
        task.stage = Stage.PUBLISHING
        emit(task, max(task.lastPercent, PUBLISHING_PERCENT), STATE_RUNNING)
        ioExecutor.execute {
            try {
                val resolvedMetadata =
                    checkNotNull(task.captureMetadataPolicy) {
                        "Capture metadata policy is missing"
                    }.resolvedMetadata()
                captureMetadataVerifier.verify(task.tempFile, resolvedMetadata)
                log(
                    "task=${task.id} capture metadata verified " +
                        "captureTimePresent=${resolvedMetadata.hasCaptureTime} " +
                        "locationPresent=${resolvedMetadata.hasLocation}",
                )
                val publishedUri =
                    mediaStoreSaver.publishVideo(
                        tempFile = task.tempFile,
                        requestedName = task.request.outputFileName,
                        outputTreeUri = task.request.outputTreeUri,
                        dateTakenEpochMs = resolvedMetadata.captureTimeEpochMs,
                    ) {
                        task.cancelRequested || disposed || Thread.currentThread().isInterrupted
                    }
                if (task.cancelRequested || disposed) {
                    discardPublished(task, publishedUri)
                    mainHandler.post {
                        runCatching { task.tempFile.delete() }
                        try {
                            if (!disposed && isCurrent(task)) finishCancelled(task)
                        } finally {
                            task.publicationBoundary.complete()
                        }
                    }
                } else {
                    // Always post this final cleanup, even if dispose() happens after the I/O
                    // check. The main-thread callback re-checks cancellation/disposal atomically
                    // with terminal state publication.
                    mainHandler.post {
                        if (disposed || !isCurrent(task) || task.cancelRequested) {
                            discardPublished(task, publishedUri)
                            runCatching { task.tempFile.delete() }
                            try {
                                if (!disposed && isCurrent(task)) finishCancelled(task)
                            } finally {
                                task.publicationBoundary.complete()
                            }
                            return@post
                        }
                        try {
                            cleanupTempAndRecovery(task)
                            task.stage = Stage.FINISHED
                            activeTask = null
                            emit(task, 100.0, STATE_SUCCESS, outputUri = publishedUri)
                            log("task=${task.id} success output=$publishedUri")
                        } finally {
                            task.publicationBoundary.complete()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is PublicationCleanupException && !error.cleanupConfirmed) {
                    task.retainRecovery = true
                    log("task=${task.id} publication cleanup unconfirmed; recovery retained")
                }
                if (task.cancelRequested || disposed) {
                    mainHandler.post {
                        try {
                            if (isCurrent(task)) finishCancelled(task)
                        } finally {
                            task.publicationBoundary.complete()
                        }
                    }
                } else {
                    mainHandler.post {
                        try {
                            if (!disposed && isCurrent(task)) {
                                fail(task, mapPublicationFailure(error), error)
                            }
                        } finally {
                            task.publicationBoundary.complete()
                        }
                    }
                }
            }
        }
    }

    private fun handleTransformerError(
        task: ActiveTask,
        exportException: ExportException,
        wasHdrToneMapping: Boolean,
    ) {
        requireMainThread()
        if (!isCurrent(task) || task.failureProbeStarted) return
        task.failureProbeStarted = true
        mainHandler.removeCallbacks(task.progressPoller)
        log("task=${task.id} ${exportFailureDiagnostic(task, exportException)}")
        ioExecutor.execute {
            val sourceAccess = sourceAccessProbe.probe(task.request.sourceUri)
            postToMain {
                if (!isCurrent(task)) return@postToMain
                log(
                    "task=${task.id} source access after export failure " +
                        sourceAccess.toLogString(),
                )
                val failure =
                    sourceAccess.toFailureAtExport(task.sourceAccessAtStart)
                        ?: EngineErrorMapper.fromExportErrorCode(
                            errorCode = exportException.errorCode,
                            wasHdrToneMapping = wasHdrToneMapping,
                        )
                fail(task, failure, exportException)
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
        val phase = task.stage.wireName
        if (state == STATE_RUNNING) {
            val now = SystemClock.elapsedRealtime()
            val phaseChanged = phase != task.lastEmittedPhase
            val modeChanged = task.actualVideoEncodingMode != task.lastEmittedVideoEncodingMode
            if (
                !phaseChanged &&
                !modeChanged &&
                task.lastRunningEventAtMs != NO_RUNNING_EVENT &&
                now - task.lastRunningEventAtMs < PROGRESS_INTERVAL_MS
            ) {
                return
            }
            task.lastRunningEventAtMs = now
        }
        task.lastPercent = monotonic
        task.lastEmittedPhase = phase
        task.lastEmittedVideoEncodingMode = task.actualVideoEncodingMode
        runCatching {
            task.listener.onEvent(
                EngineProgressEvent(
                    taskId = task.id,
                    percent = monotonic,
                    state = state,
                    phase = phase,
                    actualVideoEncodingMode = task.actualVideoEncodingMode,
                    outputUri = outputUri,
                    errorCode = failure?.code?.wireName,
                    errorMessage = failure?.message,
                ),
            )
        }.onFailure { error -> log("task=${task.id} event callback failed ${error.stackTraceToString()}") }
    }

    private fun ensureStorage(
        estimate: StorageEstimate,
        requiresPublicDestination: Boolean,
    ) {
        val cacheRoot = appContext.cacheDir
        val cacheAvailable = StatFs(cacheRoot.absolutePath).availableBytes
        if (!requiresPublicDestination) {
            if (
                !hasSufficientStorage(
                    estimate,
                    cacheAvailable,
                    publicAvailableBytes = 0L,
                    sharesStoragePool = null,
                    requiresPublicDestination = false,
                )
            ) {
                throw EngineOperationException(
                    EngineFailure(
                        EngineErrorCode.INSUFFICIENT_STORAGE,
                        "存储空间不足：应用缓存需 " +
                            "${estimate.cacheRequiredBytes / MEBIBYTE} MiB 可用空间；" +
                            "自定义文件夹空间由系统在保存时确认",
                    ),
                )
            }
            return
        }

        val publicRoot = publicStorageRoot()
        val publicAvailable = StatFs(publicRoot.absolutePath).availableBytes
        val sharesStoragePool = sharesStoragePool(cacheRoot, publicRoot)
        if (
            !hasSufficientStorage(
                estimate,
                cacheAvailable,
                publicAvailable,
                sharesStoragePool,
                requiresPublicDestination = true,
            )
        ) {
            val requirement =
                if (sharesStoragePool == false) {
                    "应用缓存需 ${estimate.cacheRequiredBytes / MEBIBYTE} MiB，" +
                        "系统影片目录需 ${estimate.publicRequiredBytes / MEBIBYTE} MiB 可用空间"
                } else {
                    "临时文件与系统影片目录的共享存储需 " +
                        "${estimate.sharedPoolRequiredBytes / MEBIBYTE} MiB 可用空间"
                }
            throw EngineOperationException(
                EngineFailure(
                    EngineErrorCode.INSUFFICIENT_STORAGE,
                    "存储空间不足：$requirement",
                ),
            )
        }
    }

    private fun sharesStoragePool(cacheRoot: File, publicRoot: File): Boolean? {
        val storageManager = appContext.getSystemService(StorageManager::class.java) ?: return null
        return runCatching {
            storageManager.getUuidForPath(cacheRoot) == storageManager.getUuidForPath(publicRoot)
        }.onFailure { error ->
            log("storage volume identity unavailable ${error.javaClass.simpleName}")
        }.getOrNull()
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
            is CropMappingException -> error.failure
            is VideoMetadataException ->
                if (error.code == VideoMetadataException.SOURCE_CORRUPTED) {
                    EngineFailure(EngineErrorCode.SOURCE_CORRUPTED, error.message)
                } else {
                    sourceAccessFailureFrom(error)
                        ?: EngineFailure(EngineErrorCode.UNKNOWN, error.message)
                }
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun mapPublicationFailure(error: Throwable): EngineFailure =
        if (error is CaptureMetadataVerificationException) {
            EngineFailure(EngineErrorCode.CAPTURE_METADATA_FAILED)
        } else {
            EngineErrorMapper.fromThrowable(error)
        }

    private fun exportFailureDiagnostic(
        task: ActiveTask,
        error: ExportException,
    ): String {
        val codec = error.codecInfo
        val codecText =
            if (codec == null) {
                "none"
            } else {
                "name=${codec.name},video=${codec.isVideo},decoder=${codec.isDecoder}," +
                    "format=${codec.configurationFormat}"
            }
        val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val causeText =
            causes.joinToString(separator = " <- ") { cause ->
                "${cause.javaClass.name}:${cause.message.orEmpty().replace('\n', ' ')}"
            }
        val platformCodecText =
            causes
                .filterIsInstance<MediaCodec.CodecException>()
                .joinToString(separator = ";") { cause ->
                    "diagnostic=${cause.diagnosticInfo},recoverable=${cause.isRecoverable}," +
                        "transient=${cause.isTransient}"
                }.ifEmpty { "none" }
        return "export failure code=${error.errorCode} name=${error.errorCodeName} " +
            "phase=${task.stage.wireName} percent=${task.lastPercent} codec={$codecText} " +
            "platformCodec={$platformCodecText} causes={$causeText}"
    }

    private fun videoMimeType(codec: VideoCodec): String =
        when (codec) {
            VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            VideoCodec.H264 -> MimeTypes.VIDEO_H264
        }


    private fun isCurrent(task: ActiveTask): Boolean = !disposed && activeTask === task

    private fun postToMain(action: () -> Unit) {
        if (!disposed) mainHandler.post { if (!disposed) action() }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        runCatching {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .versionName
                .orEmpty()
                .ifEmpty { "unknown" }
        }.getOrDefault("unknown")

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
        var captureMetadataPolicy: CaptureMetadataPolicy? = null
        var sourceAccessAtStart: SourceAccessProbeResult? = null
        var compatibleDecoderNames: Set<String> = emptySet()
        var compatibleEncoderNames: Set<String> = emptySet()
        val publicationBoundary = PublicationBoundary()
        var transformer: Transformer? = null
        var failureProbeStarted: Boolean = false
        var stage: Stage = Stage.PREPARING
        var lastPercent: Double = 0.0
        var lastEmittedPhase: String? = null
        var actualVideoEncodingMode: VideoEncoderMode = VideoEncoderMode.UNKNOWN
        var lastEmittedVideoEncodingMode: VideoEncoderMode? = null
        var lastRunningEventAtMs: Long = NO_RUNNING_EVENT
        val progressPoller = Runnable { scheduleProgress(this) }
    }

    private enum class Stage(val wireName: String) {
        PREPARING("preparing"),
        TRANSFORMING("encoding"),
        PUBLISHING("publishing"),
        CANCELLING("cancelling"),
        FINISHED("finished"),
    }

    private companion object {
        const val STATE_RUNNING = "running"
        const val STATE_SUCCESS = "success"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
        const val PROGRESS_INTERVAL_MS = 500L
        const val NO_RUNNING_EVENT = -1L
        const val PUBLISHING_PERCENT = 99.0
        const val MEBIBYTE = 1024L * 1024L
        const val MAX_CAUSE_DEPTH = 12
    }
}
