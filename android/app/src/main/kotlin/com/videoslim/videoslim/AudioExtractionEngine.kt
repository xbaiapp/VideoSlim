package com.videoslim.videoslim

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

internal class AudioExtractionEngine(
    context: Context,
    private val mediaStoreSaver: MediaStoreSaver = MediaStoreSaver(context),
    private val recoveryStore: TaskRecoveryStore = TaskRecoveryStore(context),
    private val metadataReader: AudioMetadataReader = AudioMetadataReader(context),
    private val losslessExtractor: LosslessAudioExtractor = LosslessAudioExtractor(context),
    private val sourceAccessProbe: SourceAccessProbe = SourceAccessProbe(context),
    private val logger: (String) -> Unit = {},
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tempDirectory = File(appContext.cacheDir, "transcode")
    private var activeTask: AudioTask? = null
    @Volatile private var disposed = false

    fun start(
        request: AudioExtractRequest,
        listener: EngineProgressListener,
    ): String {
        requireMainThread()
        check(!disposed) { "Engine has been disposed" }
        check(activeTask == null) { "An audio extraction task is already running" }
        if (!tempDirectory.exists() && !tempDirectory.mkdirs()) {
            throw EngineOperationException(EngineFailure(EngineErrorCode.UNKNOWN, "无法创建音频处理临时目录"))
        }
        val task =
            AudioTask(
                id = UUID.randomUUID().toString(),
                request = request,
                tempFile = File(tempDirectory, "${UUID.randomUUID()}.m4a"),
                listener = listener,
            )
        try {
            recoveryStore.begin(
                taskId = task.id,
                tempFileName = task.tempFile.name,
                expectedOutputDisplayName = request.outputFileName,
                mediaKind = OutputMediaKind.AUDIO_M4A,
            )
            task.recoveryStarted = true
        } catch (error: Throwable) {
            throw EngineOperationException(
                EngineFailure(EngineErrorCode.UNKNOWN, "无法建立音频任务恢复日志，请重试"),
                error,
            )
        }
        activeTask = task
        log(
            "taskKind=audio_extraction task=${task.id} start mode=${request.mode.wireName} " +
                "requestedBitrate=${request.bitrate} outputName=${request.outputFileName} " +
                "outputLocation=${request.outputLocationLabel} customOutput=${request.outputTreeUri != null}",
        )
        emit(task, 0.0, TaskRuntimeSnapshot.STATE_RUNNING)
        // Defer worker launch until the service has received and stored the internal task ID.
        // A tiny copy must not reach PublicationObserver while ProcessingService.engineTaskId is null.
        mainHandler.post {
            if (isCurrent(task)) ioExecutor.execute { prepare(task) }
        }
        return task.id
    }

    fun cancel(taskId: String) {
        requireMainThread()
        val task = activeTask
            ?: throw EngineOperationException(EngineFailure(EngineErrorCode.CANCELLED, "任务不存在或已结束"))
        if (task.id != taskId) {
            throw EngineOperationException(EngineFailure(EngineErrorCode.CANCELLED, "任务 ID 不匹配或已结束"))
        }
        if (task.cancelRequested) return
        task.cancelRequested = true
        task.stage = AudioStage.CANCELLING
        emit(task, task.lastPercent, TaskRuntimeSnapshot.STATE_RUNNING)
        mainHandler.removeCallbacks(task.progressPoller)
        runCatching { task.transformer?.cancel() }
        if (!task.ioOperationRunning) finishCancelled(task)
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

    private fun prepare(task: AudioTask) {
        try {
            task.ioOperationRunning = true
            checkCancellation(task)
            val sourceAccess = sourceAccessProbe.probe(task.request.sourceUri)
            task.sourceAccessAtStart = sourceAccess
            log("taskKind=audio_extraction task=${task.id} source access before metadata ${sourceAccess.toLogString()}")
            sourceAccess.toEngineFailure()?.let { failure ->
                throw EngineOperationException(failure)
            }
            val sourceMetadata =
                metadataReader.read(task.request.sourceUri) {
                    task.cancelRequested || disposed || Thread.currentThread().isInterrupted
                }
            if (
                sourceAccess.statSize != null &&
                sourceMetadata.fileSizeBytes > 0L &&
                sourceAccess.statSize != sourceMetadata.fileSizeBytes
            ) {
                throw EngineOperationException(
                    EngineFailure(
                        EngineErrorCode.SOURCE_UNAVAILABLE,
                        "所选视频在处理前发生了变化，请重新选择文件",
                    ),
                )
            }
            if (sourceMetadata.audioChannels !in 1..2) {
                throw EngineOperationException(EngineFailure(EngineErrorCode.AUDIO_CHANNEL_LAYOUT_UNSUPPORTED))
            }
            if (task.request.mode == AudioExtractMode.COPY &&
                sourceMetadata.audioMime != AudioOutputVerifier.AAC_MIME
            ) {
                throw EngineOperationException(EngineFailure(EngineErrorCode.AUDIO_COPY_UNSUPPORTED))
            }
            val plan =
                AudioExtractPlan.create(
                    request = task.request,
                    durationUs = sourceMetadata.durationMs * 1_000L,
                    sourceAudioBitrate = sourceMetadata.audioBitrate,
                    storageTopology = storageTopology(task.request),
                )
            if (!plan.hasSufficientStorage) {
                throw EngineOperationException(EngineFailure(EngineErrorCode.INSUFFICIENT_STORAGE))
            }
            task.sourceMetadata = sourceMetadata
            log(
                "taskKind=audio_extraction task=${task.id} mode=${task.request.mode.wireName} " +
                    "requestedBitrate=${task.request.bitrate} plan=$plan source=$sourceMetadata",
            )
            postToMain {
                if (!isCurrent(task)) return@postToMain
                task.ioOperationRunning = false
                if (task.cancelRequested) {
                    finishCancelled(task)
                } else if (task.request.mode == AudioExtractMode.COPY) {
                    beginCopy(task)
                } else {
                    beginAacTranscode(task)
                }
            }
        } catch (error: Throwable) {
            postToMain {
                task.ioOperationRunning = false
                fail(task, mapPreparationFailure(error), error)
            }
        }
    }

    private fun beginCopy(task: AudioTask) {
        requireMainThread()
        task.stage = AudioStage.ENCODING
        task.ioOperationRunning = true
        try {
            recoveryStore.updateStage(task.id, RecoveryStage.TRANSFORMING)
        } catch (error: Throwable) {
            task.ioOperationRunning = false
            fail(task, mapAudioFailure(error, encoding = false), error)
            return
        }
        emit(task, task.lastPercent, TaskRuntimeSnapshot.STATE_RUNNING)
        ioExecutor.execute {
            try {
                val result =
                    losslessExtractor.extract(
                        sourceUri = task.request.sourceUri,
                        outputFile = task.tempFile,
                        shouldCancel = { task.cancelRequested || disposed },
                        onProgress = { progress ->
                            postToMain {
                                if (isCurrent(task)) {
                                    emit(
                                        task,
                                        (progress * MAX_PROCESSING_PERCENT).coerceIn(0.0, MAX_PROCESSING_PERCENT),
                                        TaskRuntimeSnapshot.STATE_RUNNING,
                                    )
                                }
                            }
                        },
                    )
                log(
                    "taskKind=audio_extraction task=${task.id} mode=copy decoder=none encoder=none " +
                        "firstInputPtsUs=${result.copyResult.firstInputTimeUs} " +
                        "lastInputPtsUs=${result.copyResult.lastInputTimeUs} " +
                        "firstOutputPtsUs=0 " +
                        "lastOutputPtsUs=${result.copyResult.lastOutputTimeUs} " +
                        "samples=${result.copyResult.sampleCount} bytes=${result.copyResult.totalBytes}",
                )
                verifyAndPublish(task)
            } catch (error: Throwable) {
                retainUnconfirmedPublicationRecovery(task, error)
                val failure =
                    if (error is CancellationException || task.cancelRequested || disposed) {
                        EngineFailure(EngineErrorCode.CANCELLED)
                    } else {
                        val sourceAccess = sourceAccessProbe.probe(task.request.sourceUri)
                        log(
                            "taskKind=audio_extraction task=${task.id} source access after copy failure " +
                                sourceAccess.toLogString(),
                        )
                        sourceAccess.toFailureAtExport(task.sourceAccessAtStart)
                            ?: mapAudioFailure(error, encoding = false)
                    }
                postToMain {
                    task.ioOperationRunning = false
                    fail(task, failure, error)
                }
            }
        }
    }

    private fun beginAacTranscode(task: AudioTask) {
        requireMainThread()
        try {
            recoveryStore.updateStage(task.id, RecoveryStage.TRANSFORMING)
            task.stage = AudioStage.ENCODING
            val bitrate = requireNotNull(task.request.bitrate)
            val encoderFactory =
                LoggingEncoderFactory(
                    delegate =
                        DefaultEncoderFactory.Builder(appContext)
                            .setRequestedAudioEncoderSettings(
                                AudioEncoderSettings.Builder().setBitrate(bitrate).build(),
                            ).setEnableFallback(true)
                            .build(),
                    logger = { message ->
                        log("taskKind=audio_extraction task=${task.id} $message")
                    },
                    forceAudioEncoding = true,
                )
            val decoderFactory =
                DefaultDecoderFactory.Builder(appContext)
                    .setEnableDecoderFallback(true)
                    .setListener { codecName, initializationFailures ->
                        log(
                            "taskKind=audio_extraction task=${task.id} actual audio decoder name=$codecName " +
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
            val transformer =
                Transformer.Builder(appContext)
                    .setEncoderFactory(encoderFactory)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                mainHandler.removeCallbacks(task.progressPoller)
                                task.transformer = null
                                if (task.cancelRequested) {
                                    finishCancelled(task)
                                } else {
                                    task.ioOperationRunning = true
                                    ioExecutor.execute {
                                        try {
                                            verifyAndPublish(task)
                                        } catch (error: Throwable) {
                                            retainUnconfirmedPublicationRecovery(task, error)
                                            postToMain {
                                                task.ioOperationRunning = false
                                                fail(task, mapAudioFailure(error, encoding = true), error)
                                            }
                                        }
                                    }
                                }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                mainHandler.removeCallbacks(task.progressPoller)
                                task.transformer = null
                                handleAacTransformerError(task, exportException)
                            }
                        },
                    ).build()
            task.transformer = transformer
            val editedItem =
                EditedMediaItem.Builder(MediaItem.fromUri(task.request.sourceUri))
                    .setRemoveVideo(true)
                    .build()
            transformer.start(editedItem, task.tempFile.absolutePath)
            emit(task, task.lastPercent, TaskRuntimeSnapshot.STATE_RUNNING)
            scheduleProgress(task)
            log(
                "taskKind=audio_extraction task=${task.id} mode=aac AAC transformer started " +
                    "bitrate=$bitrate temp=${task.tempFile.name}",
            )
        } catch (error: Throwable) {
            fail(task, mapAudioFailure(error, encoding = true), error)
        }
    }

    private fun verifyAndPublish(task: AudioTask) {
        checkCancellation(task)
        val sourceAccessAtCompletion = sourceAccessProbe.probe(task.request.sourceUri)
        log(
            "taskKind=audio_extraction task=${task.id} source access after processing " +
                sourceAccessAtCompletion.toLogString(),
        )
        sourceAccessAtCompletion.toFailureAtExport(task.sourceAccessAtStart)?.let { failure ->
            throw EngineOperationException(failure)
        }
        val outputMetadata =
            metadataReader.read(task.tempFile) {
                task.cancelRequested || disposed || Thread.currentThread().isInterrupted
            }
        AudioOutputVerifier.requireValid(outputMetadata, AudioOutputVerifier.AAC_MIME)
        val sourceSpanMs = task.sourceMetadata?.sampleSpanMs() ?: 0L
        if (sourceSpanMs > 0L && kotlin.math.abs(outputMetadata.durationMs - sourceSpanMs) > 1_000L) {
            throw EngineOperationException(EngineFailure(EngineErrorCode.AUDIO_OUTPUT_INVALID))
        }
        log(
            "taskKind=audio_extraction task=${task.id} verifiedOutput=$outputMetadata " +
                "sourceSpanMs=$sourceSpanMs",
        )
        checkCancellation(task)
        postToMain {
            if (isCurrent(task) && !task.cancelRequested) {
                task.stage = AudioStage.PUBLISHING
                emit(task, PUBLISHING_PERCENT, TaskRuntimeSnapshot.STATE_RUNNING)
            }
        }
        val publishedUri =
            mediaStoreSaver.publishAudio(
                tempFile = task.tempFile,
                requestedName = task.request.outputFileName,
                outputTreeUri = task.request.outputTreeUri,
                shouldCancel = { task.cancelRequested || disposed || Thread.currentThread().isInterrupted },
            )
        if (task.cancelRequested || disposed) {
            discardPublished(task, publishedUri)
            throw CancellationException("Audio extraction cancelled after publication")
        }
        mainHandler.post publication@{
            task.ioOperationRunning = false
            if (!isCurrent(task)) {
                discardPublished(task, publishedUri)
                return@publication
            }
            if (task.cancelRequested) {
                discardPublished(task, publishedUri)
                finishCancelled(task)
                return@publication
            }
            cleanupTempAndRecovery(task)
            task.stage = AudioStage.FINISHED
            activeTask = null
            emit(task, 100.0, TaskRuntimeSnapshot.STATE_SUCCESS, outputUri = publishedUri)
            log(
                "taskKind=audio_extraction task=${task.id} terminal=success output=$publishedUri " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - task.startedAtElapsedMs}",
            )
        }
    }

    private fun scheduleProgress(task: AudioTask) {
        if (!isCurrent(task) || task.stage != AudioStage.ENCODING) return
        val transformer = task.transformer ?: return
        runCatching {
            val holder = ProgressHolder()
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                emit(
                    task,
                    max(task.lastPercent, holder.progress.coerceIn(0, 99).toDouble()),
                    TaskRuntimeSnapshot.STATE_RUNNING,
                )
            }
        }.onFailure { log("audio task=${task.id} progress query failed ${it.stackTraceToString()}") }
        if (isCurrent(task) && task.stage == AudioStage.ENCODING) {
            mainHandler.postDelayed(task.progressPoller, PROGRESS_INTERVAL_MS)
        }
    }

    private fun handleAacTransformerError(
        task: AudioTask,
        exportException: ExportException,
    ) {
        requireMainThread()
        if (!isCurrent(task) || task.failureProbeStarted) return
        task.failureProbeStarted = true
        ioExecutor.execute {
            val sourceAccess = sourceAccessProbe.probe(task.request.sourceUri)
            postToMain {
                if (!isCurrent(task)) return@postToMain
                log(
                    "taskKind=audio_extraction task=${task.id} source access after AAC failure " +
                        sourceAccess.toLogString(),
                )
                val failure =
                    sourceAccess.toFailureAtExport(task.sourceAccessAtStart)
                        ?: mapExportFailure(exportException)
                fail(task, failure, exportException)
            }
        }
    }

    private fun fail(
        task: AudioTask,
        failure: EngineFailure,
        error: Throwable,
    ) {
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
        task.stage = AudioStage.FINISHED
        activeTask = null
        emit(task, task.lastPercent, TaskRuntimeSnapshot.STATE_FAILED, failure = failure)
        log(
            "taskKind=audio_extraction task=${task.id} terminal=failed code=${failure.code.wireName} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - task.startedAtElapsedMs} " +
                "message=${failure.message} stack=${error.stackTraceToString()}",
        )
    }

    private fun finishCancelled(task: AudioTask) {
        requireMainThread()
        if (!isCurrent(task)) return
        mainHandler.removeCallbacks(task.progressPoller)
        runCatching { task.transformer?.cancel() }
        cleanupTempAndRecovery(task)
        task.stage = AudioStage.FINISHED
        activeTask = null
        emit(
            task,
            task.lastPercent,
            TaskRuntimeSnapshot.STATE_CANCELLED,
            failure = EngineFailure(EngineErrorCode.CANCELLED),
        )
        log(
            "taskKind=audio_extraction task=${task.id} terminal=cancelled " +
                "elapsedMs=${SystemClock.elapsedRealtime() - task.startedAtElapsedMs}",
        )
    }

    private fun emit(
        task: AudioTask,
        percent: Double,
        state: String,
        outputUri: String? = null,
        failure: EngineFailure? = null,
    ) {
        val monotonic = max(task.lastPercent, percent.coerceIn(0.0, 100.0))
        if (state == TaskRuntimeSnapshot.STATE_RUNNING) {
            val now = SystemClock.elapsedRealtime()
            val phaseChanged = task.stage.wireName != task.lastEmittedPhase
            if (!phaseChanged && task.lastRunningEventAtMs >= 0L &&
                now - task.lastRunningEventAtMs < PROGRESS_INTERVAL_MS
            ) {
                return
            }
            task.lastRunningEventAtMs = now
        }
        task.lastPercent = monotonic
        task.lastEmittedPhase = task.stage.wireName
        runCatching {
            task.listener.onEvent(
                EngineProgressEvent(
                    taskId = task.id,
                    percent = monotonic,
                    state = state,
                    phase = task.stage.wireName,
                    outputUri = outputUri,
                    errorCode = failure?.code?.wireName,
                    errorMessage = failure?.message,
                ),
            )
        }.onFailure { log("audio task=${task.id} event callback failed ${it.stackTraceToString()}") }
    }

    private fun mapPreparationFailure(error: Throwable): EngineFailure =
        when (error) {
            is EngineOperationException -> error.failure
            is AudioMetadataException ->
                when (error.code) {
                    AudioMetadataException.NO_AUDIO_TRACK -> EngineFailure(EngineErrorCode.AUDIO_TRACK_MISSING)
                    AudioMetadataException.SOURCE_PERMISSION_LOST ->
                        EngineFailure(EngineErrorCode.SOURCE_PERMISSION_LOST, error.message)
                    AudioMetadataException.SOURCE_CORRUPTED ->
                        EngineFailure(EngineErrorCode.SOURCE_CORRUPTED, error.message)
                    else -> EngineFailure(EngineErrorCode.SOURCE_PROVIDER_FAILED, error.message)
                }
            is CancellationException -> EngineFailure(EngineErrorCode.CANCELLED)
            else -> EngineErrorMapper.fromThrowable(error)
        }

    private fun mapAudioFailure(
        error: Throwable,
        encoding: Boolean,
    ): EngineFailure = mapAudioPipelineFailure(error, encoding)

    private fun mapExportFailure(error: ExportException): EngineFailure =
        when (error.errorCode) {
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            -> EngineFailure(EngineErrorCode.AUDIO_DECODING_FAILED)
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            -> EngineFailure(EngineErrorCode.AUDIO_ENCODING_FAILED)
            else -> EngineFailure(EngineErrorCode.AUDIO_OUTPUT_INVALID)
        }

    private fun storageTopology(request: AudioExtractRequest): AudioStorageTopology {
        val cacheAvailable = StatFs(appContext.cacheDir.absolutePath).availableBytes
        if (request.outputTreeUri != null) {
            return AudioStorageTopology(
                cacheAvailableBytes = cacheAvailable,
                publicAvailableBytes = Long.MAX_VALUE,
                sharesStoragePool = false,
            )
        }
        val publicRoot = publicMusicRoot()
        val publicAvailable = StatFs(publicRoot.absolutePath).availableBytes
        return AudioStorageTopology(
            cacheAvailableBytes = cacheAvailable,
            publicAvailableBytes = publicAvailable,
            sharesStoragePool = sharesStoragePool(appContext.cacheDir, publicRoot),
        )
    }

    private fun sharesStoragePool(cacheRoot: File, publicRoot: File): Boolean? {
        val storageManager = appContext.getSystemService(StorageManager::class.java) ?: return null
        return runCatching {
            storageManager.getUuidForPath(cacheRoot) == storageManager.getUuidForPath(publicRoot)
        }.onFailure { error ->
            log("audio storage volume identity unavailable ${error.javaClass.simpleName}")
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun publicMusicRoot(): File {
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return if (music.exists()) music else Environment.getExternalStorageDirectory()
    }

    private fun checkCancellation(task: AudioTask) {
        if (task.cancelRequested || disposed || Thread.currentThread().isInterrupted) {
            throw CancellationException("Audio extraction cancelled")
        }
    }

    private fun retainUnconfirmedPublicationRecovery(task: AudioTask, error: Throwable) {
        if (shouldRetainAudioRecovery(error)) {
            task.retainRecovery = true
            log(
                "taskKind=audio_extraction task=${task.id} " +
                    "publication cleanup unconfirmed; recovery retained",
            )
        }
    }

    private fun discardPublished(task: AudioTask, outputUri: String) {
        val boundaryRecorded = runCatching { recoveryStore.markDiscarding(task.id) }.isSuccess
        val deleted = mediaStoreSaver.deletePublished(outputUri)
        if (!boundaryRecorded || !deleted) task.retainRecovery = true
    }

    private fun cleanupTempAndRecovery(task: AudioTask) {
        val removed = !task.tempFile.exists() || task.tempFile.delete() || !task.tempFile.exists()
        if (!removed || task.retainRecovery) return
        if (task.recoveryStarted) {
            runCatching { recoveryStore.clear(task.id) }
                .onSuccess { task.recoveryStarted = false }
                .onFailure { log("audio task=${task.id} recovery clear failed ${it.stackTraceToString()}") }
        }
    }

    private fun isCurrent(task: AudioTask): Boolean = !disposed && activeTask === task

    private fun postToMain(action: () -> Unit) {
        if (!disposed) mainHandler.post { if (!disposed) action() }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AudioExtractionEngine must be called on the main looper"
        }
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private enum class AudioStage(val wireName: String) {
        PREPARING(TaskRuntimeSnapshot.PHASE_PREPARING),
        ENCODING(TaskRuntimeSnapshot.PHASE_ENCODING),
        PUBLISHING(TaskRuntimeSnapshot.PHASE_PUBLISHING),
        CANCELLING(TaskRuntimeSnapshot.PHASE_CANCELLING),
        FINISHED(TaskRuntimeSnapshot.PHASE_FINISHED),
    }

    private inner class AudioTask(
        val id: String,
        val request: AudioExtractRequest,
        val tempFile: File,
        val listener: EngineProgressListener,
    ) {
        @Volatile var cancelRequested = false
        @Volatile var ioOperationRunning = false
        var transformer: Transformer? = null
        var sourceMetadata: AudioMetadata? = null
        var sourceAccessAtStart: SourceAccessProbeResult? = null
        @Volatile var stage = AudioStage.PREPARING
        var lastPercent = 0.0
        var lastEmittedPhase: String? = null
        var lastRunningEventAtMs = -1L
        var recoveryStarted = false
        @Volatile var retainRecovery = false
        var failureProbeStarted = false
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        val progressPoller = Runnable { scheduleProgress(this) }
    }

    private companion object {
        const val MAX_PROCESSING_PERCENT = 99.0
        const val PUBLISHING_PERCENT = 99.0
        const val PROGRESS_INTERVAL_MS = 500L
    }
}

private fun AudioMetadata.sampleSpanMs(): Long {
    val first = firstSampleTimeUs ?: return durationMs
    val last = lastSampleTimeUs ?: return durationMs
    return ((last - first).coerceAtLeast(0L) / 1_000L).takeIf { it > 0L } ?: durationMs
}

internal fun mapAudioPipelineFailure(
    error: Throwable,
    encoding: Boolean,
): EngineFailure {
    when (error) {
        is EngineOperationException -> return error.failure
        is CancellationException -> return EngineFailure(EngineErrorCode.CANCELLED)
        is AudioMetadataException -> return EngineFailure(EngineErrorCode.AUDIO_OUTPUT_INVALID)
    }
    val generic = EngineErrorMapper.fromThrowable(error)
    if (generic.code != EngineErrorCode.UNKNOWN) return generic
    return EngineFailure(
        if (error is IOException || !encoding) {
            EngineErrorCode.AUDIO_OUTPUT_INVALID
        } else {
            EngineErrorCode.AUDIO_ENCODING_FAILED
        },
    )
}

internal fun shouldRetainAudioRecovery(error: Throwable): Boolean =
    error is PublicationCleanupException && !error.cleanupConfirmed
