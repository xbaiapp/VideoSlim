package com.videoslim.videoslim

import kotlin.math.max
import kotlin.math.roundToInt

internal enum class HdrMode {
    SDR,
    TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
}

internal data class StorageEstimate(
    val videoBytes: Long,
    val audioBytes: Long,
    val overheadBytes: Long,
    val outputBytes: Long,
    val cacheRequiredBytes: Long,
    val publicRequiredBytes: Long,
)

internal data class TranscodePlan(
    val outputDimensions: VideoDimensions,
    val presentationRequired: Boolean,
    val hdrMode: HdrMode,
    val storageEstimate: StorageEstimate,
) {
    companion object {
        fun create(
            request: ProcessRequest,
            metadata: VideoMetadata,
            sdkInt: Int,
        ): TranscodePlan {
            val sourceDimensions = validateDisplayDimensions(metadata)
            val outputDimensions = scaleForLongEdge(sourceDimensions, request.longEdge)
            val hdrMode = selectHdrMode(metadata.isHdr, sdkInt)

            return TranscodePlan(
                outputDimensions = outputDimensions,
                presentationRequired = outputDimensions != sourceDimensions,
                hdrMode = hdrMode,
                storageEstimate = estimateStorage(request, metadata),
            )
        }

        private fun validateDisplayDimensions(metadata: VideoMetadata): VideoDimensions {
            if (metadata.displayWidth <= 0 || metadata.displayHeight <= 0) {
                throw TranscodePlanException(
                    EngineFailure(
                        code = EngineErrorCode.SOURCE_CORRUPTED,
                        message = "无法读取源视频分辨率，文件可能已损坏",
                    ),
                )
            }
            return VideoDimensions(metadata.displayWidth, metadata.displayHeight)
        }

        private fun scaleForLongEdge(
            source: VideoDimensions,
            requestedLongEdge: Int?,
        ): VideoDimensions {
            if (requestedLongEdge == null || max(source.width, source.height) <= requestedLongEdge) {
                return source
            }

            return if (source.width >= source.height) {
                VideoDimensions(
                    width = requestedLongEdge,
                    height = scaledEvenDimension(source.height, source.width, requestedLongEdge),
                )
            } else {
                VideoDimensions(
                    width = scaledEvenDimension(source.width, source.height, requestedLongEdge),
                    height = requestedLongEdge,
                )
            }
        }

        private fun scaledEvenDimension(
            shortEdge: Int,
            longEdge: Int,
            requestedLongEdge: Int,
        ): Int =
            ((shortEdge.toDouble() * requestedLongEdge / longEdge) / 2.0)
                .roundToInt()
                .times(2)
                .coerceAtLeast(MIN_ENCODER_DIMENSION)

        private fun selectHdrMode(isHdr: Boolean, sdkInt: Int): HdrMode {
            if (!isHdr) return HdrMode.SDR
            if (sdkInt < MIN_OPEN_GL_TONE_MAPPING_SDK) {
                throw TranscodePlanException(
                    EngineFailure(
                        code = EngineErrorCode.UNKNOWN,
                        message = "HDR 视频转为 SDR 需要 Android 10 或更高版本",
                    ),
                )
            }
            return HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        }

        private fun estimateStorage(
            request: ProcessRequest,
            metadata: VideoMetadata,
        ): StorageEstimate {
            val videoBytes = bitrateDurationBytes(metadata.durationMs, request.videoBitrate)
            val audioBytes =
                when {
                    metadata.audioMime == null -> 0L
                    request.audioMode == AudioMode.REMOVE -> 0L
                    request.audioMode == AudioMode.REENCODE ->
                        bitrateDurationBytes(metadata.durationMs, request.audioBitrate ?: 0)
                    else ->
                        bitrateDurationBytes(
                            metadata.durationMs,
                            metadata.audioBitrate ?: CONSERVATIVE_COPY_AUDIO_BITRATE,
                        )
                }
            val overheadBytes =
                max(
                    MIN_OVERHEAD_BYTES,
                    metadata.fileSizeBytes.coerceAtLeast(0L) / SOURCE_OVERHEAD_DIVISOR,
                )
            val outputBytes = safeAdd(safeAdd(videoBytes, audioBytes), overheadBytes)
            val cacheRequiredBytes =
                safeAdd(outputBytes, STORAGE_HEADROOM_BYTES)
            val publicRequiredBytes = safeAdd(outputBytes, STORAGE_HEADROOM_BYTES)

            return StorageEstimate(
                videoBytes = videoBytes,
                audioBytes = audioBytes,
                overheadBytes = overheadBytes,
                outputBytes = outputBytes,
                cacheRequiredBytes = cacheRequiredBytes,
                publicRequiredBytes = publicRequiredBytes,
            )
        }

        private fun bitrateDurationBytes(durationMs: Long, bitrate: Int): Long {
            if (durationMs <= 0L || bitrate <= 0) return 0L

            // Split before multiplying so ordinary long videos remain precise while truly
            // unrepresentable estimates saturate instead of wrapping around.
            val whole = safeMultiply(durationMs / BITS_PER_MILLISECOND, bitrate.toLong())
            val remainder =
                safeMultiply(durationMs % BITS_PER_MILLISECOND, bitrate.toLong()) /
                    BITS_PER_MILLISECOND
            return safeAdd(whole, remainder)
        }

        private fun safeMultiply(left: Long, right: Long): Long =
            when {
                left <= 0L || right <= 0L -> 0L
                left > Long.MAX_VALUE / right -> Long.MAX_VALUE
                else -> left * right
            }

        private fun safeAdd(left: Long, right: Long): Long =
            if (left >= Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        private const val MIN_ENCODER_DIMENSION = 2
        private const val MIN_OPEN_GL_TONE_MAPPING_SDK = 29
        private const val CONSERVATIVE_COPY_AUDIO_BITRATE = 256_000
        private const val BITS_PER_MILLISECOND = 8_000L
        private const val SOURCE_OVERHEAD_DIVISOR = 20L
        private const val MIN_OVERHEAD_BYTES = 16L * 1024L * 1024L
        private const val STORAGE_HEADROOM_BYTES = 32L * 1024L * 1024L

    }
}

internal class TranscodePlanException(
    val failure: EngineFailure,
) : IllegalArgumentException(failure.message)
