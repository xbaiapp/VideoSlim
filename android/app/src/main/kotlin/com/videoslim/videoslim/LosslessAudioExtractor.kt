package com.videoslim.videoslim

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

internal data class LosslessAudioExtractResult(
    val copyResult: EncodedAudioCopyResult,
    val audioMime: String,
    val channels: Int,
    val sampleRate: Int,
)

internal class LosslessAudioExtractor(context: Context) {
    private val appContext = context.applicationContext

    @Throws(IOException::class, CancellationException::class, EngineOperationException::class)
    fun extract(
        sourceUri: String,
        outputFile: File,
        shouldCancel: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): LosslessAudioExtractResult {
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Unable to replace temporary audio output")
        }
        outputFile.parentFile?.let { parent ->
            if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
                throw IOException("Unable to create temporary audio directory")
            }
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var completed = false
        try {
            extractor.setDataSource(appContext, Uri.parse(sourceUri), null)
            val track = findFirstAudioTrack(extractor)
            val format = track.format
            val mime = format.string(MediaFormat.KEY_MIME).orEmpty()
            val profile = format.integer(MediaFormat.KEY_AAC_PROFILE)
            if (!isSupportedLosslessCopyFormat(mime, profile)) {
                throw EngineOperationException(
                    EngineFailure(
                        EngineErrorCode.AUDIO_COPY_UNSUPPORTED,
                        "无损直提仅支持 AAC 音轨，请改用 AAC 压缩模式",
                    ),
                )
            }
            val channels = format.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            if (channels !in 1..2) {
                throw EngineOperationException(
                    EngineFailure(EngineErrorCode.AUDIO_CHANNEL_LAYOUT_UNSUPPORTED),
                )
            }
            val sampleRate = format.integer(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            if (sampleRate <= 0) {
                throw EngineOperationException(
                    EngineFailure(EngineErrorCode.SOURCE_CORRUPTED, "音轨采样率无效"),
                )
            }
            val durationUs = format.long(MediaFormat.KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
            val bufferBytes = boundedAudioSampleBufferSize(format.integer(MediaFormat.KEY_MAX_INPUT_SIZE))

            positionSelectedTrackAtStart(
                selectTrack = { extractor.selectTrack(track.index) },
                currentSampleTimeUs = { extractor.sampleTime },
                seekToStart = { extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) },
            )
            val activeMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val outputTrack = activeMuxer.addTrack(format)
            activeMuxer.start()
            muxerStarted = true
            val copyResult =
                copyEncodedAudioSamples(
                    source =
                    MediaExtractorSampleSource(
                        access = FrameworkMediaExtractorSampleAccess(extractor),
                        supportsIndexedSampleSize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                    ),
                    sink = MediaMuxerSampleSink(activeMuxer, outputTrack),
                    durationUs = durationUs,
                    requestedBufferBytes = bufferBytes,
                    shouldCancel = shouldCancel,
                    onProgress = onProgress,
                )
            activeMuxer.stop()
            muxerStarted = false
            completed = true
            return LosslessAudioExtractResult(
                copyResult = copyResult,
                audioMime = mime,
                channels = channels,
                sampleRate = sampleRate,
            )
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            if (!completed) runCatching { outputFile.delete() }
        }
    }

    private fun findFirstAudioTrack(extractor: MediaExtractor): AudioTrack {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.string(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return AudioTrack(index, format)
            }
        }
        throw EngineOperationException(EngineFailure(EngineErrorCode.AUDIO_TRACK_MISSING))
    }

    private data class AudioTrack(
        val index: Int,
        val format: MediaFormat,
    )
}

internal fun isSupportedLosslessCopyFormat(mime: String?, profile: Int?): Boolean =
    mime == AudioOutputVerifier.AAC_MIME && AudioOutputVerifier.isSupportedCopyProfile(profile)
internal interface MediaExtractorSampleAccess {
    val sampleTime: Long
    val sampleFlags: Int
    val sampleSize: Long

    fun readSampleData(buffer: ByteBuffer): Int

    fun advance(): Boolean
}

private class FrameworkMediaExtractorSampleAccess(
    private val extractor: MediaExtractor,
) : MediaExtractorSampleAccess {
    override val sampleTime: Long get() = extractor.sampleTime
    override val sampleFlags: Int get() = extractor.sampleFlags

    @get:RequiresApi(Build.VERSION_CODES.P)
    override val sampleSize: Long get() = extractor.sampleSize

    override fun readSampleData(buffer: ByteBuffer): Int = extractor.readSampleData(buffer, 0)

    override fun advance(): Boolean = extractor.advance()
}

internal class MediaExtractorSampleSource(
    private val access: MediaExtractorSampleAccess,
    private val supportsIndexedSampleSize: Boolean,
) : EncodedAudioSampleSource {
    override val sampleTimeUs: Long
        get() = access.sampleTime

    override val sampleFlags: Int
        get() = access.sampleFlags

    override val indexedSampleSize: Long?
        get() = if (supportsIndexedSampleSize) access.sampleSize else null

    override fun readSampleData(buffer: ByteBuffer): Int = access.readSampleData(buffer)

    override fun advance(): Boolean = access.advance()
}

private class MediaMuxerSampleSink(
    private val muxer: MediaMuxer,
    private val trackIndex: Int,
) : EncodedAudioSampleSink {
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun writeSampleData(
        buffer: ByteBuffer,
        info: EncodedAudioSampleInfo,
    ) {
        bufferInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
    }
}

private fun MediaFormat.string(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

private fun MediaFormat.integer(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
        ?: runCatching { getLong(key).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() }.getOrNull()
}

private fun MediaFormat.long(key: String): Long? {
    if (!containsKey(key)) return null
    return runCatching { getLong(key) }.getOrNull()
        ?: runCatching { getInteger(key).toLong() }.getOrNull()
}
