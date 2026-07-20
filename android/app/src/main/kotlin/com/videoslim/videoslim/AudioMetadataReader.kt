package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

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
    val sampleTimesMonotonic: Boolean,
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
        const val SOURCE_CORRUPTED = "SOURCE_CORRUPTED"
        const val SOURCE_PERMISSION_LOST = "SOURCE_PERMISSION_LOST"
        const val UNKNOWN = "UNKNOWN"
    }
}

internal class AudioMetadataReader(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    fun read(uriString: String): AudioMetadata =
        if (uriString.startsWith("content://")) read(Uri.parse(uriString)) else read(java.io.File(uriString))

    fun read(uri: Uri): AudioMetadata {
        val openable = readOpenableMetadata(uri)
        return readConfigured(uri.toString(), openable) { extractor, retriever ->
            extractor.setDataSource(appContext, uri, null)
            retriever.setDataSource(appContext, uri)
        }
    }

    fun read(file: java.io.File): AudioMetadata =
        readConfigured(
            sourceUri = file.absolutePath,
            openable = OpenableMetadata(file.name, file.length()),
        ) { extractor, retriever ->
            extractor.setDataSource(file.absolutePath)
            retriever.setDataSource(file.absolutePath)
        }

    private fun readConfigured(
        sourceUri: String,
        openable: OpenableMetadata,
        configure: (MediaExtractor, MediaMetadataRetriever) -> Unit,
    ): AudioMetadata {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
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
            val timing = inspectSampleTimes(extractor, firstAudioTrack)
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
                sampleTimesMonotonic = timing.monotonic,
                audioTrackIndex = firstAudioTrack,
                audioProfile = audioFormat.intValue(MediaFormat.KEY_AAC_PROFILE),
            )
        } catch (error: AudioMetadataException) {
            throw error
        } catch (error: SecurityException) {
            throw AudioMetadataException(
                AudioMetadataException.SOURCE_PERMISSION_LOST,
                "无法访问音频文件",
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
    ): SampleTiming {
        extractor.selectTrack(trackIndex)
        var first: Long? = null
        var previous: Long? = null
        var last: Long? = null
        var count = 0L
        var sampleBytes = 0L
        var monotonic = true
        while (true) {
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0L) break
            if (first == null) first = sampleTime
            if (previous != null && sampleTime <= previous) monotonic = false
            previous = sampleTime
            last = sampleTime
            count += 1L
            extractor.sampleSize.takeIf { it > 0L }?.let { size ->
                sampleBytes =
                    if (sampleBytes > Long.MAX_VALUE - size) Long.MAX_VALUE else sampleBytes + size
            }
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(trackIndex)
        return SampleTiming(first, last, count, sampleBytes, monotonic)
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

    private data class SampleTiming(
        val firstSampleTimeUs: Long?,
        val lastSampleTimeUs: Long?,
        val sampleCount: Long,
        val sampleBytes: Long,
        val monotonic: Boolean,
    )

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
