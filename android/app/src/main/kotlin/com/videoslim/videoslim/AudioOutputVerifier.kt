package com.videoslim.videoslim

import java.io.IOException
import kotlin.math.abs

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
        if (!metadata.sampleTimesMonotonic) throw IOException("Published audio sample timestamps are not monotonic")
        val firstSampleTimeUs = metadata.firstSampleTimeUs ?: throw IOException("Published audio has no first timestamp")
        if (firstSampleTimeUs < 0L || firstSampleTimeUs > MAX_START_OFFSET_US) {
            throw IOException("Published audio does not start near zero")
        }
        val lastSampleTimeUs = metadata.lastSampleTimeUs ?: throw IOException("Published audio has no last timestamp")
        if (lastSampleTimeUs < firstSampleTimeUs) throw IOException("Published audio timestamp range is invalid")
        val sampleSpanUs = lastSampleTimeUs - firstSampleTimeUs
        val declaredDurationUs = metadata.durationMs * MICROSECONDS_PER_MILLISECOND
        if (abs(declaredDurationUs - sampleSpanUs) > MAX_DURATION_DELTA_US) {
            throw IOException("Published audio sample span does not match declared duration")
        }
        if (
            metadata.audioMime == AAC_MIME &&
            (allowedAacProfiles.isEmpty() || metadata.audioProfile !in allowedAacProfiles)
        ) {
            throw IOException("Published audio AAC profile is missing or unsupported")
        }
        return metadata
    }

    private const val MICROSECONDS_PER_MILLISECOND = 1_000L
    private const val MAX_DURATION_DELTA_US = 1_000_000L
}
