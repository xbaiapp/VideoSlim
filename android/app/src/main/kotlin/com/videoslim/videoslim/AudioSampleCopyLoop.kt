package com.videoslim.videoslim

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.max

internal const val DEFAULT_AUDIO_SAMPLE_BUFFER_BYTES = 1024 * 1024
internal const val MAX_AUDIO_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024
internal const val AUDIO_SAMPLE_DIGEST_VERSION = 0x01

internal data class AudioSampleDigest(
    val version: Int,
    val sha256Hex: String,
) {
    init {
        require(version in 0x00..0xff) { "Audio sample digest version must fit in one byte" }
        require(sha256Hex.length == SHA_256_HEX_LENGTH && sha256Hex.all { it in LOWERCASE_HEX_DIGITS }) {
            "Audio sample digest must be canonical lowercase SHA-256 hex"
        }
    }

    override fun toString(): String =
        "AudioSampleDigest(version=$version, sha256Hex=<redacted>)"

    private companion object {
        const val SHA_256_HEX_LENGTH = 64
        const val LOWERCASE_HEX_DIGITS = "0123456789abcdef"
    }
}

/**
 * Streams the versioned sequence of physically read encoded AAC samples into SHA-256.
 * The caller's ByteBuffer position and limit are never modified.
 */
internal class AudioSampleDigestAccumulator(
    private val version: Int = AUDIO_SAMPLE_DIGEST_VERSION,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val header = ByteBuffer.allocate(SAMPLE_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
    private var sampleIndex = 0L
    private var finished = false

    init {
        require(version in 0x00..0xff) { "Audio sample digest version must fit in one byte" }
        digest.update(version.toByte())
    }

    fun addSample(
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
    ) {
        check(!finished) { "Audio sample digest is already finished" }
        require(length > 0) { "Audio sample digest length must be positive" }
        if (offset < 0 || offset > buffer.capacity() - length) {
            throw IndexOutOfBoundsException("Audio sample digest range is outside the buffer")
        }
        val nextSampleIndex = Math.incrementExact(sampleIndex)
        header.clear()
        header.putLong(sampleIndex)
        header.putLong(length.toLong())
        header.flip()
        digest.update(header)

        val payload: ByteBuffer = buffer.duplicate()
        payload.clear()
        payload.position(offset)
        payload.limit(offset + length)
        digest.update(payload)
        sampleIndex = nextSampleIndex
    }

    fun finish(): AudioSampleDigest {
        check(!finished) { "Audio sample digest is already finished" }
        finished = true
        return AudioSampleDigest(version, digest.digest().toLowercaseHex())
    }

    private companion object {
        const val SAMPLE_HEADER_BYTES = 16
    }
}

private fun ByteArray.toLowercaseHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = digits[value ushr 4]
        chars[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(chars)
}

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
    val sampleDigest: AudioSampleDigest,
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
    val sampleDigest = AudioSampleDigestAccumulator()
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
        sampleDigest.addSample(buffer, info.offset, info.size)
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
        throw NoReadableAudioSamplesException()
    }
    onProgress(1.0)
    return EncodedAudioCopyResult(
        sampleCount = sampleCount,
        totalBytes = totalBytes,
        firstInputTimeUs = firstInputTimeUs,
        lastInputTimeUs = lastInputTimeUs,
        lastOutputTimeUs = lastOutputTimeUs,
        usesIndexedPhysicalSampleSizes = indexedSizeMode == true,
        sampleDigest = sampleDigest.finish(),
    )
}
