package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

internal data class AudioMetadata(
    val sourceUri: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val container: String,
    val audioMime: String,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val audioBitrate: Int?,
    val audioTrackCount: Int,
    val videoTrackCount: Int,
    val firstSampleTimeUs: Long?,
    val lastSampleTimeUs: Long?,
    val sampleCount: Long,
    val sampleBytes: Long,
    val sampleDigest: AudioSampleDigest,
    val usesIndexedPhysicalSampleSizes: Boolean = false,
    val sampleTimesMonotonic: Boolean,
    val maxSampleDeltaUs: Long?,
    val audioTrackIndex: Int = 0,
    val audioProfile: Int? = null,
) {
    fun toChannelMap(): Map<String, Any?> =
        linkedMapOf(
            "uri" to sourceUri,
            "fileName" to fileName,
            "fileSizeBytes" to fileSizeBytes,
            "durationMs" to durationMs,
            "container" to container,
            "audioCodec" to audioMime,
            "audioChannels" to audioChannels,
            "audioSampleRate" to audioSampleRate,
            "audioBitrate" to audioBitrate,
        )
}

internal class AudioMetadataException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val NO_AUDIO_TRACK = "NO_AUDIO_TRACK"
        const val NO_READABLE_SAMPLES = "NO_READABLE_SAMPLES"
        const val SOURCE_CORRUPTED = "SOURCE_CORRUPTED"
        const val SOURCE_PERMISSION_LOST = "SOURCE_PERMISSION_LOST"
        const val UNKNOWN = "UNKNOWN"
    }
}

internal class AudioMetadataReader(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    fun read(
        uriString: String,
        shouldCancel: () -> Boolean = { false },
    ): AudioMetadata =
        if (uriString.startsWith("content://")) {
            read(Uri.parse(uriString), shouldCancel)
        } else {
            read(java.io.File(uriString), shouldCancel)
        }

    fun read(
        uri: Uri,
        shouldCancel: () -> Boolean = { false },
    ): AudioMetadata =
        try {
            checkAudioMetadataCancellation(shouldCancel)
            val openable = readOpenableMetadata(uri)
            readConfigured(uri.toString(), openable, shouldCancel) { extractor, retriever ->
                extractor.setDataSource(appContext, uri, null)
                retriever.setDataSource(appContext, uri)
            }
        } catch (error: SecurityException) {
            throw audioSourcePermissionException(error)
        }

    fun read(
        file: java.io.File,
        shouldCancel: () -> Boolean = { false },
    ): AudioMetadata =
        readConfigured(
            sourceUri = file.absolutePath,
            openable = OpenableMetadata(file.name, file.length()),
            shouldCancel = shouldCancel,
        ) { extractor, retriever ->
            extractor.setDataSource(file.absolutePath)
            retriever.setDataSource(file.absolutePath)
        }

    private fun readConfigured(
        sourceUri: String,
        openable: OpenableMetadata,
        shouldCancel: () -> Boolean,
        configure: (MediaExtractor, MediaMetadataRetriever) -> Unit,
    ): AudioMetadata {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            checkAudioMetadataCancellation(shouldCancel)
            configure(extractor, retriever)
            var firstAudioTrack = -1
            var firstAudioFormat: MediaFormat? = null
            var audioTrackCount = 0
            var videoTrackCount = 0
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.stringValue(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith(AUDIO_MIME_PREFIX) -> {
                        audioTrackCount += 1
                        if (firstAudioTrack < 0) {
                            firstAudioTrack = trackIndex
                            firstAudioFormat = format
                        }
                    }
                    mime.startsWith(VIDEO_MIME_PREFIX) -> videoTrackCount += 1
                }
            }
            val audioFormat =
                firstAudioFormat
                    ?: throw AudioMetadataException(
                        AudioMetadataException.NO_AUDIO_TRACK,
                        "所选文件没有可提取的音轨",
                    )
            val audioMime =
                audioFormat.stringValue(MediaFormat.KEY_MIME)
                    ?: throw AudioMetadataException(
                        AudioMetadataException.SOURCE_CORRUPTED,
                        "无法读取音频轨道格式",
                    )
            val channels = audioFormat.intValue(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 } ?: 0
            val sampleRate = audioFormat.intValue(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 } ?: 0
            val durationMs =
                audioFormat.longValue(MediaFormat.KEY_DURATION)
                    ?.div(MICROSECONDS_PER_MILLISECOND)
                    ?.takeIf { it >= 0L }
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0L }
                    ?: 0L
            val declaredBitrate =
                audioFormat.intValue(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }
            val timing = inspectSampleTimes(
                extractor,
                firstAudioTrack,
                audioFormat,
                shouldCancel,
            )
            val estimatedBitrate =
                if (declaredBitrate == null && timing.sampleBytes > 0L && durationMs > 0L) {
                    ((timing.sampleBytes.toDouble() * BITS_PER_BYTE * MILLIS_PER_SECOND) / durationMs)
                        .toLong()
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                } else {
                    null
                }
            return AudioMetadata(
                sourceUri = sourceUri,
                fileName = openable.fileName,
                fileSizeBytes = openable.fileSizeBytes,
                durationMs = durationMs,
                container =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        ?: sourceUri.takeIf { it.startsWith("content://") }
                            ?.let { contentResolver.getType(Uri.parse(it)) }
                        ?: "",
                audioMime = audioMime,
                audioChannels = channels,
                audioSampleRate = sampleRate,
                audioBitrate = declaredBitrate ?: estimatedBitrate,
                audioTrackCount = audioTrackCount,
                videoTrackCount = videoTrackCount,
                firstSampleTimeUs = timing.firstSampleTimeUs,
                lastSampleTimeUs = timing.lastSampleTimeUs,
                sampleCount = timing.sampleCount,
                sampleBytes = timing.sampleBytes,
                sampleDigest = timing.sampleDigest,
                usesIndexedPhysicalSampleSizes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                sampleTimesMonotonic = timing.monotonic,
                maxSampleDeltaUs = timing.maxSampleDeltaUs,
                audioTrackIndex = firstAudioTrack,
                audioProfile = audioFormat.intValue(MediaFormat.KEY_AAC_PROFILE),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: AudioMetadataException) {
            throw error
        } catch (error: SecurityException) {
            throw audioSourcePermissionException(error)
        } catch (error: NoReadableAudioSamplesException) {
            throw AudioMetadataException(
                AudioMetadataException.NO_READABLE_SAMPLES,
                "无法读取音轨样本",
                error,
            )
        } catch (error: IOException) {
            throw AudioMetadataException(AudioMetadataException.UNKNOWN, "无法读取音频文件", error)
        } catch (error: RuntimeException) {
            throw AudioMetadataException(
                AudioMetadataException.SOURCE_CORRUPTED,
                "音频文件可能已损坏或格式不受支持",
                error,
            )
        } finally {
            runCatching { extractor.release() }
            runCatching { retriever.release() }
        }
    }

    private fun inspectSampleTimes(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat,
        shouldCancel: () -> Boolean,
    ): AudioSampleTiming {
        positionSelectedTrackAtStart(
            selectTrack = { extractor.selectTrack(trackIndex) },
            currentSampleTimeUs = { extractor.sampleTime },
            seekToStart = { extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) },
        )
        // Keep one bounded buffer and physically read every indexed sample on
        // every supported API. The extra byte makes an over-cap legacy sample
        // observable instead of accepting a possibly truncated cap-sized read.
        format.intValue(MediaFormat.KEY_MAX_INPUT_SIZE)?.let(::boundedAudioSampleBufferSize)
        val sampleBuffer = ByteBuffer.allocate(MAX_AUDIO_SAMPLE_BUFFER_BYTES + 1)
        return try {
            scanAudioSampleMetadata(
                sampleTimeUs = { extractor.sampleTime },
                samplePayloadBytes = { consumeVerifiedPayload ->
                    val indexedSize =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            extractor.sampleSize
                        } else {
                            null
                        }
                    readVerifiedAudioSamplePayload(
                        sampleBuffer = sampleBuffer,
                        indexedSampleSize = indexedSize,
                        readSampleData = { buffer -> extractor.readSampleData(buffer, 0) },
                        consumeVerifiedPayload = consumeVerifiedPayload,
                    )
                },
                advance = { extractor.advance() },
                shouldCancel = shouldCancel,
            )
        } finally {
            runCatching { extractor.unselectTrack(trackIndex) }
        }
    }

    private fun readOpenableMetadata(uri: Uri): OpenableMetadata {
        var fileName: String? = null
        var fileSizeBytes: Long? = null
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) fileName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        fileSizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
            }
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            // Provider metadata is optional; decoding remains authoritative.
        }
        if (fileSizeBytes == null) {
            try {
                fileSizeBytes =
                    contentResolver.openAssetFileDescriptor(uri, READ_MODE)?.use { descriptor ->
                        descriptor.length.takeIf { it >= 0L }
                    }
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                // Unknown length remains zero.
            }
        }
        return OpenableMetadata(
            fileName = fileName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().ifBlank { "audio" },
            fileSizeBytes = fileSizeBytes ?: 0L,
        )
    }

    private data class OpenableMetadata(
        val fileName: String,
        val fileSizeBytes: Long,
    )

    private companion object {
        const val AUDIO_MIME_PREFIX = "audio/"
        const val VIDEO_MIME_PREFIX = "video/"
        const val READ_MODE = "r"
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
        const val MILLIS_PER_SECOND = 1_000.0
        const val BITS_PER_BYTE = 8.0
    }
}

internal data class AudioSampleTiming(
    val firstSampleTimeUs: Long?,
    val lastSampleTimeUs: Long?,
    val sampleCount: Long,
    val sampleBytes: Long,
    val sampleDigest: AudioSampleDigest,
    val monotonic: Boolean,
    val maxSampleDeltaUs: Long?,
)

/**
 * Some framework/provider combinations expose track metadata but no current sample immediately
 * after selectTrack(). Retry once from stream start before concluding that the selected track
 * contains no physical samples.
 */
internal fun positionSelectedTrackAtStart(
    selectTrack: () -> Unit,
    currentSampleTimeUs: () -> Long,
    seekToStart: () -> Unit,
) {
    selectTrack()
    if (currentSampleTimeUs() < 0L) seekToStart()
}

internal class NoReadableAudioSamplesException :
    IOException("Audio track contains no readable samples")

internal typealias VerifiedPayloadConsumer = (ByteBuffer, Int, Int) -> Unit

internal fun scanAudioSampleMetadata(
    sampleTimeUs: () -> Long,
    samplePayloadBytes: (VerifiedPayloadConsumer) -> Long,
    advance: () -> Boolean,
    shouldCancel: () -> Boolean,
): AudioSampleTiming {
    var first: Long? = null
    var previous: Long? = null
    var last: Long? = null
    var count = 0L
    var totalBytes = 0L
    var monotonic = true
    var maxSampleDeltaUs: Long? = null
    val sampleDigest = AudioSampleDigestAccumulator()
    while (true) {
        checkAudioMetadataCancellation(shouldCancel)
        val sampleTime = sampleTimeUs()
        if (sampleTime < 0L) break
        if (first == null) first = sampleTime
        if (previous != null) {
            val delta = sampleTime - previous
            if (delta <= 0L) {
                monotonic = false
            } else if (maxSampleDeltaUs == null || delta > maxSampleDeltaUs) {
                maxSampleDeltaUs = delta
            }
        }
        previous = sampleTime
        last = sampleTime
        count += 1L
        checkAudioMetadataCancellation(shouldCancel)
        var payloadDelivered = false
        var deliveredPayloadLength = 0
        val size =
            samplePayloadBytes { buffer, offset, length ->
                if (payloadDelivered) {
                    throw IOException("Audio sample payload was delivered more than once")
                }
                payloadDelivered = true
                deliveredPayloadLength = length
                sampleDigest.addSample(buffer, offset, length)
            }
        if (size <= 0L) throw IOException("Audio sample payload is unreadable")
        if (!payloadDelivered || deliveredPayloadLength.toLong() != size) {
            throw IOException("Audio sample payload delivery did not match its physical read")
        }
        totalBytes =
            if (totalBytes > Long.MAX_VALUE - size) Long.MAX_VALUE else totalBytes + size
        checkAudioMetadataCancellation(shouldCancel)
        if (!advance()) break
    }
    checkAudioMetadataCancellation(shouldCancel)
    if (count <= 0L) throw NoReadableAudioSamplesException()
    return AudioSampleTiming(
        firstSampleTimeUs = first,
        lastSampleTimeUs = last,
        sampleCount = count,
        sampleBytes = totalBytes,
        sampleDigest = sampleDigest.finish(),
        monotonic = monotonic,
        maxSampleDeltaUs = maxSampleDeltaUs,
    )
}

/**
 * Reads one complete encoded sample into a reusable bounded buffer.
 *
 * API 28+ exposes the indexed sample size, which must exactly match the
 * physical read. Older APIs still perform the same physical read and reject
 * zero, failed, or over-product-cap results. The buffer has one sentinel byte
 * beyond the product cap so a legacy over-cap read cannot masquerade as a
 * complete cap-sized sample.
 */
@Throws(IOException::class)
internal fun readVerifiedAudioSamplePayload(
    sampleBuffer: ByteBuffer,
    indexedSampleSize: Long?,
    readSampleData: (ByteBuffer) -> Int,
    consumeVerifiedPayload: VerifiedPayloadConsumer,
): Long {
    if (indexedSampleSize != null && indexedSampleSize !in 1..MAX_AUDIO_SAMPLE_BUFFER_BYTES.toLong()) {
        throw IOException("Indexed audio sample size is invalid or exceeds the verification cap")
    }
    sampleBuffer.clear()
    val bytesRead = readSampleData(sampleBuffer)
    if (bytesRead <= 0 || bytesRead > MAX_AUDIO_SAMPLE_BUFFER_BYTES) {
        throw IOException("Audio sample payload is unreadable or exceeds the verification cap")
    }
    if (indexedSampleSize != null && bytesRead.toLong() != indexedSampleSize) {
        throw IOException("Audio sample payload read did not exactly match its indexed size")
    }
    consumeVerifiedPayload(sampleBuffer, 0, bytesRead)
    return bytesRead.toLong()
}

private fun checkAudioMetadataCancellation(shouldCancel: () -> Boolean) {
    if (shouldCancel() || Thread.currentThread().isInterrupted) {
        throw CancellationException("Audio metadata scan cancelled")
    }
}

internal fun audioSourcePermissionException(error: SecurityException): AudioMetadataException =
    AudioMetadataException(
        AudioMetadataException.SOURCE_PERMISSION_LOST,
        "无法访问音频文件",
        error,
    )

private fun MediaFormat.stringValue(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

private fun MediaFormat.intValue(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
        ?: runCatching { getLong(key).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() }.getOrNull()
}

private fun MediaFormat.longValue(key: String): Long? {
    if (!containsKey(key)) return null
    return runCatching { getLong(key) }.getOrNull()
        ?: runCatching { getInteger(key).toLong() }.getOrNull()
}
