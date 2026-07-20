package com.videoslim.videoslim

import android.media.MediaCodecInfo

internal enum class VideoRateControlStatus {
    HONORED,
    TARGET_EXCEEDED,
    INVALID_OUTPUT,
    NOT_VERIFIABLE,
}

internal data class VideoRateControlCheck(
    val status: VideoRateControlStatus,
    val actualOutputBytes: Long,
    val maximumOutputBytes: Long?,
) {
    val isHonored: Boolean
        get() = status == VideoRateControlStatus.HONORED || status == VideoRateControlStatus.NOT_VERIFIABLE
}

/** Product-level contract for codecs that must honor the requested target size. */
internal object VideoRateControlPolicy {
    const val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR

    fun check(
        actualOutputBytes: Long,
        maximumOutputBytes: Long,
        durationMs: Long,
    ): VideoRateControlCheck {
        if (actualOutputBytes <= 0L) {
            return VideoRateControlCheck(
                status = VideoRateControlStatus.INVALID_OUTPUT,
                actualOutputBytes = actualOutputBytes,
                maximumOutputBytes = null,
            )
        }
        if (durationMs <= 0L || maximumOutputBytes <= 0L) {
            return VideoRateControlCheck(
                status = VideoRateControlStatus.NOT_VERIFIABLE,
                actualOutputBytes = actualOutputBytes,
                maximumOutputBytes = null,
            )
        }

        return VideoRateControlCheck(
            status =
                if (actualOutputBytes <= maximumOutputBytes) {
                    VideoRateControlStatus.HONORED
                } else {
                    VideoRateControlStatus.TARGET_EXCEEDED
                },
            actualOutputBytes = actualOutputBytes,
            maximumOutputBytes = maximumOutputBytes,
        )
    }
}
