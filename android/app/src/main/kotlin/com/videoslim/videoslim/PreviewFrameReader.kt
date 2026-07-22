package com.videoslim.videoslim

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal class PreviewFrameReader(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun read(
        sourceUri: String,
        timeMs: Long,
    ): ByteArray {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, Uri.parse(sourceUri))
            val frame =
                retriever.getFrameAtTime(
                    timeMs.coerceAtMost(Long.MAX_VALUE / MICROS_PER_MILLISECOND) *
                        MICROS_PER_MILLISECOND,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: throw PreviewFrameException("无法读取这个位置的预览画面")
            val scaled = scaleToLongEdge(frame, MAX_PREVIEW_LONG_EDGE)
            return try {
                ByteArrayOutputStream().use { output ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw PreviewFrameException("无法生成预览画面")
                    }
                    output.toByteArray()
                }
            } finally {
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
        } catch (error: PreviewFrameException) {
            throw error
        } catch (error: Throwable) {
            throw PreviewFrameException("无法读取视频预览，请重新选择视频", error)
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal companion object {
        const val MAX_PREVIEW_LONG_EDGE = 1280
        const val JPEG_QUALITY = 88
        const val MICROS_PER_MILLISECOND = 1000L
    }
}

internal fun scaleToLongEdge(
    frame: Bitmap,
    maximumLongEdge: Int,
): Bitmap {
    require(maximumLongEdge > 0)
    val longEdge = max(frame.width, frame.height)
    if (longEdge <= maximumLongEdge) return frame
    val scale = maximumLongEdge.toDouble() / longEdge.toDouble()
    return Bitmap.createScaledBitmap(
        frame,
        (frame.width * scale).roundToInt().coerceAtLeast(1),
        (frame.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

internal class PreviewFrameException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
