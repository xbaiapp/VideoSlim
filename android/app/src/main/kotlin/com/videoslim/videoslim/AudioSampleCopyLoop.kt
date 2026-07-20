package com.videoslim.videoslim

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.max

internal const val DEFAULT_AUDIO_SAMPLE_BUFFER_BYTES = 1024 * 1024
internal const val MAX_AUDIO_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024

internal interface EncodedAudioSampleSource {
    val sampleTimeUs: Long
    val sampleFlags: Int

    fun readSampleData(buffer: ByteBuffer): Int

    fun advance(): Boolean
}

internal interface EncodedAudioSampleSink {
    fun writeSampleData(
        buffer: ByteBuffer,
        info: EncodedAudioSampleInfo,
    )
}

internal data class EncodedAudioSampleInfo(
    val offset: Int,
    val size: Int,
    val presentationTimeUs: Long,
    val flags: Int,
)

internal data class EncodedAudioCopyResult(
    val sampleCount: Long,
    val totalBytes: Long,
    val firstInputTimeUs: Long,
    val lastOutputTimeUs: Long,
)

internal fun boundedAudioSampleBufferSize(declaredMaxInputSize: Int?): Int {
    if (declaredMaxInputSize == null || declaredMaxInputSize <= 0) {
        return DEFAULT_AUDIO_SAMPLE_BUFFER_BYTES
    }
    require(declaredMaxInputSize <= MAX_AUDIO_SAMPLE_BUFFER_BYTES) {
        "Declared audio sample size exceeds the bounded copy buffer"
    }
    return max(DEFAULT_AUDIO_SAMPLE_BUFFER_BYTES, declaredMaxInputSize)
}

@Throws(IOException::class, CancellationException::class)
internal fun copyEncodedAudioSamples(
    source: EncodedAudioSampleSource,
    sink: EncodedAudioSampleSink,
    durationUs: Long,
    requestedBufferBytes: Int,
    shouldCancel: () -> Boolean,
    onProgress: (Double) -> Unit = {},
): EncodedAudioCopyResult {
    require(requestedBufferBytes in 1..MAX_AUDIO_SAMPLE_BUFFER_BYTES) {
        "Audio sample buffer must be positive and bounded"
    }
    val buffer = ByteBuffer.allocate(requestedBufferBytes)
    var firstInputTimeUs: Long? = null
    var lastOutputTimeUs = -1L
    var sampleCount = 0L
    var totalBytes = 0L
    while (true) {
        if (shouldCancel() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Audio extraction cancelled")
        }
        val inputTimeUs = source.sampleTimeUs
        if (inputTimeUs < 0L) break
        buffer.clear()
        val size = source.readSampleData(buffer)
        if (size < 0) break
        if (size > buffer.capacity()) {
            throw IOException("Audio sample exceeds the bounded copy buffer")
        }
        val baseTimeUs = firstInputTimeUs ?: inputTimeUs.also { firstInputTimeUs = it }
        val rebasedTimeUs = (inputTimeUs - baseTimeUs).coerceAtLeast(0L)
        val outputTimeUs =
            if (lastOutputTimeUs < 0L) {
                0L
            } else {
                max(rebasedTimeUs, lastOutputTimeUs + 1L)
            }
        val info =
            EncodedAudioSampleInfo(
                offset = 0,
                size = size,
                presentationTimeUs = outputTimeUs,
                flags = source.sampleFlags,
            )
        buffer.position(0)
        buffer.limit(size)
        sink.writeSampleData(buffer, info)
        sampleCount += 1L
        totalBytes += size.toLong()
        lastOutputTimeUs = outputTimeUs
        val progress =
            if (durationUs > 0L) {
                ((inputTimeUs - baseTimeUs).toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        onProgress(progress)
        if (!source.advance()) break
    }
    if (sampleCount <= 0L || firstInputTimeUs == null) {
        throw IOException("Audio track contains no readable samples")
    }
    onProgress(1.0)
    return EncodedAudioCopyResult(
        sampleCount = sampleCount,
        totalBytes = totalBytes,
        firstInputTimeUs = firstInputTimeUs,
        lastOutputTimeUs = lastOutputTimeUs,
    )
}
