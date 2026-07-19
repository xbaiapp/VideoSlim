package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

internal data class VideoMetadata(
    val sourceUri: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val container: String,
    val videoMime: String,
    val storageWidth: Int,
    val storageHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotationDegrees: Int,
    val frameRate: Double,
    val videoBitrate: Int,
    val audioMime: String?,
    val audioChannels: Int?,
    val audioSampleRate: Int?,
    val audioBitrate: Int?,
    val isHdr: Boolean,
    val videoProfile: Int? = null,
    val videoLevel: Int? = null,
) {
    fun toChannelMap(): Map<String, Any?> =
        linkedMapOf(
            "uri" to sourceUri,
            "fileName" to fileName,
            "fileSizeBytes" to fileSizeBytes,
            "durationMs" to durationMs,
            "container" to container,
            "videoCodec" to videoMime,
            "width" to displayWidth,
            "height" to displayHeight,
            "rotationDegrees" to rotationDegrees,
            "frameRate" to frameRate,
            "videoBitrate" to videoBitrate,
            "audioCodec" to audioMime,
            "audioChannels" to audioChannels,
            "audioSampleRate" to audioSampleRate,
            "audioBitrate" to audioBitrate,
            "isHdr" to isHdr,
        )
}

internal class VideoMetadataException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val SOURCE_CORRUPTED = "SOURCE_CORRUPTED"
        const val UNKNOWN = "UNKNOWN"
    }
}

internal class VideoMetadataReader(context: Context) {
    private val contentResolver: ContentResolver = context.contentResolver
    private val appContext: Context = context.applicationContext

    fun read(uriString: String): VideoMetadata = read(Uri.parse(uriString))

    fun read(uri: Uri): VideoMetadata {
        val openableMetadata = readOpenableMetadataSafely(uri)
        val retriever = MediaMetadataRetriever()
        var extractor: MediaExtractor? = null

        try {
            val activeExtractor = MediaExtractor()
            extractor = activeExtractor
            retriever.setDataSource(appContext, uri)
            activeExtractor.setDataSource(appContext, uri, null)

            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (trackIndex in 0 until activeExtractor.trackCount) {
                val format = activeExtractor.getTrackFormat(trackIndex)
                when {
                    videoFormat == null && format.mimeType()?.startsWith(VIDEO_MIME_PREFIX) == true -> {
                        videoFormat = format
                    }
                    audioFormat == null && format.mimeType()?.startsWith(AUDIO_MIME_PREFIX) == true -> {
                        audioFormat = format
                    }
                }
            }

            val requiredVideoFormat =
                videoFormat
                    ?: throw VideoMetadataException(
                        code = VideoMetadataException.SOURCE_CORRUPTED,
                        message = CORRUPTED_MESSAGE,
                    )
            val videoMime =
                requiredVideoFormat.mimeType()
                    ?: throw VideoMetadataException(
                        code = VideoMetadataException.SOURCE_CORRUPTED,
                        message = CORRUPTED_MESSAGE,
                    )

            val rawRotation =
                requiredVideoFormat.intValue(MediaFormat.KEY_ROTATION)
                    ?: retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?: 0
            val normalizedRotation = VideoGeometry.normalizeRotation(rawRotation)
            val storageWidth =
                (requiredVideoFormat.intValue(MediaFormat.KEY_WIDTH)
                    ?: retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?: 0).coerceAtLeast(0)
            val storageHeight =
                (requiredVideoFormat.intValue(MediaFormat.KEY_HEIGHT)
                    ?: retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?: 0).coerceAtLeast(0)
            val displayDimensions =
                VideoGeometry.displayDimensions(
                    storageWidth = storageWidth,
                    storageHeight = storageHeight,
                    rotationDegrees = normalizedRotation,
                )
            val durationMs =
                retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.takeIf { it >= 0L }
                    ?: requiredVideoFormat
                        .longValue(MediaFormat.KEY_DURATION)
                        ?.div(MICROSECONDS_PER_MILLISECOND)
                        ?.coerceAtLeast(0L)
                    ?: 0L
            val videoBitrate =
                (requiredVideoFormat.intValue(MediaFormat.KEY_BIT_RATE)
                    ?: retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?: 0).coerceAtLeast(0)
            val colorTransfer = requiredVideoFormat.intValue(MediaFormat.KEY_COLOR_TRANSFER)

            return VideoMetadata(
                sourceUri = uri.toString(),
                fileName = openableMetadata.fileName,
                fileSizeBytes = openableMetadata.fileSizeBytes,
                durationMs = durationMs,
                container =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        ?: contentResolver.getType(uri)
                        ?: "",
                videoMime = videoMime,
                storageWidth = storageWidth,
                storageHeight = storageHeight,
                displayWidth = displayDimensions.width,
                displayHeight = displayDimensions.height,
                rotationDegrees = normalizedRotation,
                frameRate =
                    (requiredVideoFormat.doubleValue(MediaFormat.KEY_FRAME_RATE)
                        ?: retriever.metadataDouble(
                            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE,
                        )
                        ?: 0.0).coerceAtLeast(0.0),
                videoBitrate = videoBitrate,
                audioMime = audioFormat?.mimeType(),
                audioChannels =
                    audioFormat
                        ?.intValue(MediaFormat.KEY_CHANNEL_COUNT)
                        ?.takeIf { it > 0 },
                audioSampleRate =
                    audioFormat
                        ?.intValue(MediaFormat.KEY_SAMPLE_RATE)
                        ?.takeIf { it > 0 },
                audioBitrate =
                    audioFormat
                        ?.intValue(MediaFormat.KEY_BIT_RATE)
                        ?.takeIf { it > 0 },
                isHdr =
                    colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
                        colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084,
                videoProfile = requiredVideoFormat.intValue(MediaFormat.KEY_PROFILE),
                videoLevel = requiredVideoFormat.intValue(MediaFormat.KEY_LEVEL),
            )
        } catch (exception: VideoMetadataException) {
            throw exception
        } catch (exception: SecurityException) {
            throw VideoMetadataException(
                code = VideoMetadataException.UNKNOWN,
                message = ACCESS_MESSAGE,
                cause = exception,
            )
        } catch (exception: IOException) {
            throw VideoMetadataException(
                code = VideoMetadataException.UNKNOWN,
                message = ACCESS_MESSAGE,
                cause = exception,
            )
        } catch (exception: IllegalArgumentException) {
            throw VideoMetadataException(
                code = VideoMetadataException.SOURCE_CORRUPTED,
                message = CORRUPTED_MESSAGE,
                cause = exception,
            )
        } catch (exception: RuntimeException) {
            throw VideoMetadataException(
                code = VideoMetadataException.SOURCE_CORRUPTED,
                message = CORRUPTED_MESSAGE,
                cause = exception,
            )
        } finally {
            runCatching { extractor?.release() }
            runCatching { retriever.release() }
        }
    }

    private fun readOpenableMetadataSafely(uri: Uri): OpenableMetadata =
        try {
            readOpenableMetadata(uri)
        } catch (exception: SecurityException) {
            throw VideoMetadataException(
                code = VideoMetadataException.UNKNOWN,
                message = ACCESS_MESSAGE,
                cause = exception,
            )
        }

    private fun readOpenableMetadata(uri: Uri): OpenableMetadata {
        var fileName: String? = null
        var fileSizeBytes: Long? = null
        try {
            contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                            fileName = cursor.getString(nameColumn)
                        }
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            fileSizeBytes = cursor.getLong(sizeColumn).takeIf { it >= 0L }
                        }
                    }
                }
        } catch (exception: SecurityException) {
            throw exception
        } catch (_: Exception) {
            // Some document providers do not implement OpenableColumns. Media
            // decoding can still succeed, so fall back instead of rejecting it.
        }

        if (fileSizeBytes == null) {
            try {
                fileSizeBytes =
                    contentResolver.openAssetFileDescriptor(uri, READ_MODE)?.use { descriptor ->
                        descriptor.length.takeIf { it >= 0L }
                    }
            } catch (exception: SecurityException) {
                throw exception
            } catch (_: Exception) {
                // Unknown provider length is represented as zero by the wire contract.
            }
        }

        val fallbackName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE_NAME
        return OpenableMetadata(
            fileName = fileName?.takeIf { it.isNotBlank() } ?: fallbackName,
            fileSizeBytes = fileSizeBytes ?: 0L,
        )
    }

    private data class OpenableMetadata(
        val fileName: String,
        val fileSizeBytes: Long,
    )

    private companion object {
        const val VIDEO_MIME_PREFIX = "video/"
        const val AUDIO_MIME_PREFIX = "audio/"
        const val READ_MODE = "r"
        const val DEFAULT_FILE_NAME = "video"
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
        const val CORRUPTED_MESSAGE = "无法读取视频信息，文件可能已损坏或不是有效视频"
        const val ACCESS_MESSAGE = "无法访问所选视频，请确认文件仍然可用"
    }
}

private fun MediaFormat.mimeType(): String? =
    if (containsKey(MediaFormat.KEY_MIME)) getString(MediaFormat.KEY_MIME) else null

private fun MediaFormat.intValue(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
        ?: runCatching {
            getLong(key).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }.getOrNull()
        ?: runCatching { getFloat(key).toInt() }.getOrNull()
        ?: runCatching { getString(key)?.toDoubleOrNull()?.toInt() }.getOrNull()
}

private fun MediaFormat.longValue(key: String): Long? {
    if (!containsKey(key)) return null
    return runCatching { getLong(key) }.getOrNull()
        ?: runCatching { getInteger(key).toLong() }.getOrNull()
        ?: runCatching { getFloat(key).toLong() }.getOrNull()
        ?: runCatching { getString(key)?.toDoubleOrNull()?.toLong() }.getOrNull()
}

private fun MediaFormat.doubleValue(key: String): Double? {
    if (!containsKey(key)) return null
    return runCatching { getFloat(key).toDouble() }.getOrNull()
        ?: runCatching { getLong(key).toDouble() }.getOrNull()
        ?: runCatching { getInteger(key).toDouble() }.getOrNull()
        ?: runCatching { getString(key)?.toDoubleOrNull() }.getOrNull()
}


private fun MediaMetadataRetriever.metadataInt(key: Int): Int? =
    extractMetadata(key)?.trim()?.toIntOrNull()

private fun MediaMetadataRetriever.metadataLong(key: Int): Long? =
    extractMetadata(key)?.trim()?.toLongOrNull()

private fun MediaMetadataRetriever.metadataDouble(key: Int): Double? =
    extractMetadata(key)?.trim()?.toDoubleOrNull()
