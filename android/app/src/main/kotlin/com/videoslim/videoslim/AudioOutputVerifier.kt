package com.videoslim.videoslim

import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

internal object AudioOutputVerifier {
    const val AAC_MIME = "audio/mp4a-latm"
    const val MAX_START_OFFSET_US = 100_000L

    // Android MediaCodecInfo.CodecProfileLevel AAC object type values.
    const val AAC_PROFILE_LC = 2
    const val AAC_PROFILE_HE = 5
    const val AAC_PROFILE_HE_PS = 29

    /** M3 copy contract: lossless AAC-LC, HE-AAC v1, or HE-AAC v2 only. */
    val COPY_AAC_PROFILES = setOf(AAC_PROFILE_LC, AAC_PROFILE_HE, AAC_PROFILE_HE_PS)

    /** M3 forced encode contract: the output encoder and verifier both require AAC-LC. */
    val TRANSCODE_AAC_PROFILES = setOf(AAC_PROFILE_LC)

    fun isSupportedCopyProfile(profile: Int?): Boolean = profile in COPY_AAC_PROFILES

    @Throws(IOException::class)
    fun requireValid(
        metadata: AudioMetadata,
        requiredMime: String? = null,
        allowedAacProfiles: Set<Int> = COPY_AAC_PROFILES,
        expectedSource: AudioMetadata? = null,
    ): AudioMetadata {
        if (metadata.fileSizeBytes <= 0L) throw IOException("Published audio is empty")
        if (metadata.durationMs <= 0L) throw IOException("Published audio has no duration")
        if (metadata.audioTrackCount != 1) throw IOException("Published audio must contain exactly one audio track")
        if (metadata.videoTrackCount != 0) throw IOException("Published audio unexpectedly contains a video track")
        if (requiredMime != null && metadata.audioMime != requiredMime) {
            throw IOException("Published audio codec ${metadata.audioMime} did not match $requiredMime")
        }
        if (metadata.audioChannels !in 1..2) throw IOException("Published audio channel count is unsupported")
        if (metadata.audioSampleRate <= 0) throw IOException("Published audio sample rate is invalid")
        if (metadata.sampleCount <= 0L) throw IOException("Published audio has no samples")
        if (metadata.sampleBytes <= 0L) throw IOException("Published audio has no sample payload")
        if (metadata.sampleBytes > metadata.fileSizeBytes) {
            throw IOException("Published audio sample payload exceeds its physical file size")
        }
        if (!metadata.sampleTimesMonotonic) throw IOException("Published audio sample timestamps are not monotonic")
        val firstSampleTimeUs = metadata.firstSampleTimeUs ?: throw IOException("Published audio has no first timestamp")
        if (firstSampleTimeUs < 0L || firstSampleTimeUs > MAX_START_OFFSET_US) {
            throw IOException("Published audio does not start near zero")
        }
        val lastSampleTimeUs = metadata.lastSampleTimeUs ?: throw IOException("Published audio has no last timestamp")
        if (lastSampleTimeUs < firstSampleTimeUs) throw IOException("Published audio timestamp range is invalid")
        if (
            metadata.audioMime == AAC_MIME &&
            (allowedAacProfiles.isEmpty() || metadata.audioProfile !in allowedAacProfiles)
        ) {
            throw IOException("Published audio AAC profile is missing or unsupported")
        }
        requireTimelineConsistent(metadata)
        expectedSource?.let { source ->
            requireTimelineConsistent(source)
            requireCoverageConsistent(source, metadata)
        }
        return metadata
    }

    private fun requireTimelineConsistent(metadata: AudioMetadata) {
        val declaredDurationUs = durationUs(metadata.durationMs)
        val coveredDurationUs = coveredSampleDurationUs(metadata)
        val frameUs = estimatedFrameDurationUs(metadata)
        val toleranceUs = max(MIN_ROUNDING_TOLERANCE_US, frameUs * TIMELINE_TOLERANCE_FRAMES)
        if (
            absDifference(declaredDurationUs, coveredDurationUs) > toleranceUs ||
            !hasMinimumCoverage(declaredDurationUs, coveredDurationUs)
        ) {
            throw IOException("Audio sample coverage does not match declared duration")
        }
    }

    private fun requireCoverageConsistent(source: AudioMetadata, output: AudioMetadata) {
        val sourceCoverageUs = coveredSampleDurationUs(source)
        val outputCoverageUs = coveredSampleDurationUs(output)
        val toleranceUs =
            max(
                MIN_ROUNDING_TOLERANCE_US,
                saturatedAdd(
                    estimatedFrameDurationUs(source),
                    estimatedFrameDurationUs(output) * ENCODER_DELAY_TOLERANCE_FRAMES,
                ),
            )
        if (
            absDifference(sourceCoverageUs, outputCoverageUs) > toleranceUs ||
            !hasMinimumCoverage(sourceCoverageUs, outputCoverageUs)
        ) {
            throw IOException("Published audio sample coverage does not match the source")
        }
    }

    private fun coveredSampleDurationUs(metadata: AudioMetadata): Long {
        val first = metadata.firstSampleTimeUs ?: throw IOException("Audio has no first timestamp")
        val last = metadata.lastSampleTimeUs ?: throw IOException("Audio has no last timestamp")
        if (last < first) throw IOException("Audio timestamp range is invalid")
        return saturatedAdd(last - first, estimatedFrameDurationUs(metadata))
    }

    private fun estimatedFrameDurationUs(metadata: AudioMetadata): Long {
        val sampleRate = metadata.audioSampleRate
        if (sampleRate <= 0) throw IOException("Audio sample rate is invalid")
        val span =
            (metadata.lastSampleTimeUs ?: 0L) - (metadata.firstSampleTimeUs ?: 0L)
        if (metadata.sampleCount > 1L) {
            if (span <= 0L) throw IOException("Audio sample timestamps have no positive cadence")
            val observed =
                ceil(span.toDouble() / (metadata.sampleCount - 1L))
                    .toLong()
                    .coerceAtLeast(1L)
            val maximum = maximumFrameDurationUs(metadata)
            if (observed > saturatedAdd(maximum, FRAME_TIMESTAMP_ROUNDING_US)) {
                throw IOException("Audio sample cadence contains missing or sparse frames")
            }
            return observed
        }
        return maximumFrameDurationUs(metadata)
    }

    private fun maximumFrameDurationUs(metadata: AudioMetadata): Long {
        val sampleRate = metadata.audioSampleRate
        if (sampleRate <= 0) throw IOException("Audio sample rate is invalid")
        val samplesPerFrame =
            when {
                metadata.audioMime != AAC_MIME -> null
                metadata.audioProfile == AAC_PROFILE_LC -> AAC_LC_SAMPLES_PER_FRAME
                else -> HE_AAC_SAMPLES_PER_FRAME
            }
        if (samplesPerFrame != null) {
            return ceil(samplesPerFrame.toDouble() * MICROSECONDS_PER_SECOND / sampleRate)
                .toLong()
                .coerceAtLeast(1L)
        }
        return MAX_GENERAL_AUDIO_FRAME_US
    }

    private fun hasMinimumCoverage(expectedUs: Long, actualUs: Long): Boolean {
        if (expectedUs <= 0L || actualUs <= 0L) return false
        val shorter = minOf(expectedUs, actualUs)
        val longer = maxOf(expectedUs, actualUs)
        return shorter.toDouble() / longer.toDouble() >= MIN_COVERAGE_RATIO
    }

    private fun durationUs(durationMs: Long): Long =
        if (durationMs > Long.MAX_VALUE / MICROSECONDS_PER_MILLISECOND) {
            Long.MAX_VALUE
        } else {
            durationMs * MICROSECONDS_PER_MILLISECOND
        }

    private fun absDifference(left: Long, right: Long): Long =
        if (left >= right) left - right else right - left

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val MICROSECONDS_PER_MILLISECOND = 1_000L
    private const val MICROSECONDS_PER_SECOND = 1_000_000.0
    private const val AAC_LC_SAMPLES_PER_FRAME = 1_024
    private const val HE_AAC_SAMPLES_PER_FRAME = 2_048
    private const val MIN_ROUNDING_TOLERANCE_US = 5_000L
    private const val TIMELINE_TOLERANCE_FRAMES = 2L
    private const val ENCODER_DELAY_TOLERANCE_FRAMES = 2L
    private const val FRAME_TIMESTAMP_ROUNDING_US = 2_000L
    private const val MAX_GENERAL_AUDIO_FRAME_US = 120_000L
    private const val MIN_COVERAGE_RATIO = 0.75
}
