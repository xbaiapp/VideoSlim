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
    val lowerOutputBytes: Long,
    val upperOutputBytes: Long,
    val cacheRequiredBytes: Long,
    val publicRequiredBytes: Long,
    val sharedPoolRequiredBytes: Long,
)

internal fun hasSufficientStorage(
    estimate: StorageEstimate,
    cacheAvailableBytes: Long,
    publicAvailableBytes: Long,
    sharesStoragePool: Boolean?,
    requiresPublicDestination: Boolean = true,
): Boolean =
    if (!requiresPublicDestination) {
        cacheAvailableBytes >= estimate.cacheRequiredBytes
    } else if (sharesStoragePool == false) {
        cacheAvailableBytes >= estimate.cacheRequiredBytes &&
            publicAvailableBytes >= estimate.publicRequiredBytes
    } else {
        minOf(cacheAvailableBytes, publicAvailableBytes) >= estimate.sharedPoolRequiredBytes
    }

internal enum class VideoEffectKind {
    CROP,
    PRESENTATION,
}

internal data class TranscodePlan(
    val outputDimensions: VideoDimensions,
    val crop: MappedCrop?,
    val trim: TimeTrim?,
    val effectiveDurationMs: Long,
    val presentationRequired: Boolean,
    val hdrMode: HdrMode,
    val storageEstimate: StorageEstimate,
) {
    val videoEffectOrder: List<VideoEffectKind>
        get() =
            buildList {
                if (crop != null) add(VideoEffectKind.CROP)
                if (presentationRequired) add(VideoEffectKind.PRESENTATION)
            }

    companion object {
        fun create(
            request: ProcessRequest,
            metadata: VideoMetadata,
            sdkInt: Int,
        ): TranscodePlan {
            val sourceDimensions = validateDisplayDimensions(metadata)
            val mappedCrop =
                request.crop?.let { crop ->
                    CropGeometryMapper.map(
                        crop = crop,
                        displayWidth = sourceDimensions.width,
                        displayHeight = sourceDimensions.height,
                        rotationDegrees = metadata.rotationDegrees,
                    )
                }
            val effectInputDimensions = mappedCrop?.outputDimensions ?: sourceDimensions
            val outputDimensions = scaleForLongEdge(effectInputDimensions, request.longEdge)
            val hdrMode = selectHdrMode(metadata.isHdr, sdkInt)
            val trim = validateTrim(request.trim, metadata.durationMs)
            val effectiveDurationMs = trim?.durationMs ?: metadata.durationMs

            return TranscodePlan(
                outputDimensions = outputDimensions,
                crop = mappedCrop,
                trim = trim,
                effectiveDurationMs = effectiveDurationMs,
                presentationRequired = outputDimensions != effectInputDimensions,
                hdrMode = hdrMode,
                storageEstimate = estimateStorage(request, metadata, effectiveDurationMs),
            )
        }

        private fun validateTrim(trim: TimeTrim?, sourceDurationMs: Long): TimeTrim? {
            if (trim == null) return null
            if (
                trim.startMs < 0L ||
                trim.endMs <= trim.startMs ||
                trim.durationMs < MIN_TRIM_DURATION_MS ||
                sourceDurationMs <= 0L ||
                trim.endMs > sourceDurationMs
            ) {
                throw TranscodePlanException(EngineFailure(EngineErrorCode.INVALID_TRIM))
            }
            return trim
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
            effectiveDurationMs: Long,
        ): StorageEstimate {
            val videoBytes = bitrateDurationBytes(effectiveDurationMs, request.videoBitrate)
            val audioBytes =
                when {
                    metadata.audioMime == null -> 0L
                    request.audioMode == AudioMode.REMOVE -> 0L
                    request.audioMode == AudioMode.REENCODE ->
                        bitrateDurationBytes(effectiveDurationMs, request.audioBitrate ?: 0)
                    else ->
                        bitrateDurationBytes(
                            effectiveDurationMs,
                            metadata.audioBitrate ?: CONSERVATIVE_COPY_AUDIO_BITRATE,
                        )
                }
            val nominalMediaBytes = safeAdd(videoBytes, audioBytes)
            val overheadBytes = max(MIN_OVERHEAD_BYTES, nominalMediaBytes / 100L)
            val outputBytes = safeAdd(nominalMediaBytes, overheadBytes)
            val lowerOutputBytes =
                safeAdd(
                    safeAdd(safeScale(videoBytes, VBR_LOWER_PERCENT, 100L), audioBytes),
                    overheadBytes,
                )
            val upperOutputBytes =
                safeAdd(
                    safeAdd(
                        safeScale(videoBytes, VBR_UPPER_PERCENT, 100L),
                        safeScale(audioBytes, AUDIO_UPPER_PERCENT, 100L),
                    ),
                    overheadBytes,
                )
            val cacheRequiredBytes = safeAdd(upperOutputBytes, STORAGE_HEADROOM_BYTES)
            val publicRequiredBytes = safeAdd(upperOutputBytes, STORAGE_HEADROOM_BYTES)
            val sharedPoolRequiredBytes =
                safeAdd(
                    safeMultiply(upperOutputBytes, OVERLAPPING_TEMP_AND_PUBLIC_OUTPUTS),
                    STORAGE_HEADROOM_BYTES,
                )

            return StorageEstimate(
                videoBytes = videoBytes,
                audioBytes = audioBytes,
                overheadBytes = overheadBytes,
                outputBytes = outputBytes,
                lowerOutputBytes = lowerOutputBytes,
                upperOutputBytes = upperOutputBytes,
                cacheRequiredBytes = cacheRequiredBytes,
                publicRequiredBytes = publicRequiredBytes,
                sharedPoolRequiredBytes = sharedPoolRequiredBytes,
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

        private fun safeScale(
            value: Long,
            numerator: Long,
            denominator: Long,
        ): Long =
            if (value == Long.MAX_VALUE) Long.MAX_VALUE else safeMultiply(value, numerator) / denominator

        private fun safeAdd(left: Long, right: Long): Long =
            if (left >= Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        private const val MIN_ENCODER_DIMENSION = 2
        private const val MIN_OPEN_GL_TONE_MAPPING_SDK = 29
        private const val CONSERVATIVE_COPY_AUDIO_BITRATE = 128_000
        private const val BITS_PER_MILLISECOND = 8_000L
        private const val VBR_LOWER_PERCENT = 80L
        private const val VBR_UPPER_PERCENT = 200L
        private const val AUDIO_UPPER_PERCENT = 110L
        private const val MIN_OVERHEAD_BYTES = 4L * 1024L * 1024L
        private const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val OVERLAPPING_TEMP_AND_PUBLIC_OUTPUTS = 2L
        private const val MIN_TRIM_DURATION_MS = 1_000L

    }
}

internal class TranscodePlanException(
    val failure: EngineFailure,
) : IllegalArgumentException(failure.message)
