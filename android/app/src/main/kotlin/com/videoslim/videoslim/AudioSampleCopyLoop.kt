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
    /** Exact indexed sample size on API 28+, or null for the legacy sentinel path. */
    val indexedSampleSize: Long?

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
    val lastInputTimeUs: Long,
    val lastOutputTimeUs: Long,
    val usesIndexedPhysicalSampleSizes: Boolean,
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
    val buffer = ByteBuffer.allocate(requestedBufferBytes + 1)
    var indexedSizeMode: Boolean? = null
    var firstInputTimeUs: Long? = null
    var lastOutputTimeUs = -1L
    var sampleCount = 0L
    var totalBytes = 0L
    var lastInputTimeUs = -1L
    var lastProgress = 0.0
    while (true) {
        if (shouldCancel() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Audio extraction cancelled")
        }
        val inputTimeUs = source.sampleTimeUs
        if (inputTimeUs < 0L) break
        if (lastInputTimeUs >= 0L && inputTimeUs <= lastInputTimeUs) {
            throw IOException("Source audio sample timestamps are not strictly monotonic")
        }
        val indexedSampleSize = source.indexedSampleSize
        val thisSampleUsesIndex = indexedSampleSize != null
        if (indexedSizeMode != null && indexedSizeMode != thisSampleUsesIndex) {
            throw IOException("Audio sample-size evidence changed during copy")
        }
        indexedSizeMode = thisSampleUsesIndex
        if (
            indexedSampleSize != null &&
            (indexedSampleSize <= 0L || indexedSampleSize > requestedBufferBytes.toLong())
        ) {
            throw IOException("Indexed audio sample size is outside the bounded copy buffer")
        }
        buffer.clear()
        val size = source.readSampleData(buffer)
        if (size > requestedBufferBytes) {
            throw IOException("Audio sample exceeds the bounded copy buffer")
        }
        if (size <= 0) {
            throw IOException("Source audio sample payload is unreadable")
        }
        if (indexedSampleSize != null && size.toLong() != indexedSampleSize) {
            throw IOException("Physical audio sample read does not match indexed sample size")
        }
        if (shouldCancel() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Audio extraction cancelled before sample write")
        }
        val baseTimeUs = firstInputTimeUs ?: inputTimeUs.also { firstInputTimeUs = it }
        val outputTimeUs = inputTimeUs - baseTimeUs
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
        lastInputTimeUs = inputTimeUs
        lastOutputTimeUs = outputTimeUs
        val progress =
            if (durationUs > 0L) {
                ((inputTimeUs - baseTimeUs).toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        lastProgress = max(lastProgress, progress)
        onProgress(lastProgress)
        if (!source.advance()) break
    }
    if (shouldCancel() || Thread.currentThread().isInterrupted) {
        throw CancellationException("Audio extraction cancelled after sample copy")
    }
    if (sampleCount <= 0L || firstInputTimeUs == null) {
        throw IOException("Audio track contains no readable samples")
    }
    onProgress(1.0)
    return EncodedAudioCopyResult(
        sampleCount = sampleCount,
        totalBytes = totalBytes,
        firstInputTimeUs = firstInputTimeUs,
        lastInputTimeUs = lastInputTimeUs,
        lastOutputTimeUs = lastOutputTimeUs,
        usesIndexedPhysicalSampleSizes = indexedSizeMode == true,
    )
}
