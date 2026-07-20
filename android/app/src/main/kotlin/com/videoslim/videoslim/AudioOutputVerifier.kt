package com.videoslim.videoslim

import java.io.IOException

internal object AudioOutputVerifier {
    const val AAC_MIME = "audio/mp4a-latm"
    const val MAX_START_OFFSET_US = 100_000L

    @Throws(IOException::class)
    fun requireValid(
        metadata: AudioMetadata,
        requiredMime: String? = null,
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
        if (!metadata.sampleTimesMonotonic) throw IOException("Published audio sample timestamps are not monotonic")
        val firstSampleTimeUs = metadata.firstSampleTimeUs ?: throw IOException("Published audio has no first timestamp")
        if (firstSampleTimeUs < 0L || firstSampleTimeUs > MAX_START_OFFSET_US) {
            throw IOException("Published audio does not start near zero")
        }
        val lastSampleTimeUs = metadata.lastSampleTimeUs ?: throw IOException("Published audio has no last timestamp")
        if (lastSampleTimeUs < firstSampleTimeUs) throw IOException("Published audio timestamp range is invalid")
        return metadata
    }
}
