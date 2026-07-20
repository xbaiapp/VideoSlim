package com.videoslim.videoslim

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRateControlPolicyTest {
    @Test
    fun `uses constant bitrate mode for enforceable target size`() {
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            VideoRateControlPolicy.bitrateMode,
        )
    }

    @Test
    fun `accepts bounded encoded output and rejects oversized output`() {
        val exactLimit =
            VideoRateControlPolicy.check(
                actualOutputBytes = 125_000_000L,
                maximumOutputBytes = 125_000_000L,
                durationMs = 60_000L,
            )
        val oversized =
            VideoRateControlPolicy.check(
                actualOutputBytes = 125_000_001L,
                maximumOutputBytes = 125_000_000L,
                durationMs = 60_000L,
            )

        assertTrue(exactLimit.isHonored)
        assertEquals(125_000_000L, exactLimit.maximumOutputBytes)
        assertEquals(VideoRateControlStatus.TARGET_EXCEEDED, oversized.status)
        assertFalse(oversized.isHonored)
    }

    @Test
    fun `rejects the two observed Pixel HEVC overshoots`() {
        val first =
            VideoRateControlPolicy.check(
                actualOutputBytes = 1_547_379_905L,
                maximumOutputBytes = 818_409_286L,
                durationMs = 5_874_313L,
            )
        val second =
            VideoRateControlPolicy.check(
                actualOutputBytes = 1_340_449_168L,
                maximumOutputBytes = 711_584_005L,
                durationMs = 5_107_551L,
            )

        assertEquals(VideoRateControlStatus.TARGET_EXCEEDED, first.status)
        assertEquals(VideoRateControlStatus.TARGET_EXCEEDED, second.status)
    }

    @Test
    fun `rejects missing output but skips target check when duration is unavailable`() {
        val missing =
            VideoRateControlPolicy.check(
                actualOutputBytes = 0L,
                maximumOutputBytes = 125_000_000L,
                durationMs = 60_000L,
            )
        val unknownDuration =
            VideoRateControlPolicy.check(
                actualOutputBytes = 500_000_000L,
                maximumOutputBytes = 125_000_000L,
                durationMs = 0L,
            )

        assertEquals(VideoRateControlStatus.INVALID_OUTPUT, missing.status)
        assertFalse(missing.isHonored)
        assertEquals(VideoRateControlStatus.NOT_VERIFIABLE, unknownDuration.status)
        assertTrue(unknownDuration.isHonored)
    }

    @Test
    fun `maximum target size saturates instead of overflowing`() {
        val result =
            VideoRateControlPolicy.check(
                actualOutputBytes = Long.MAX_VALUE,
                maximumOutputBytes = Long.MAX_VALUE,
                durationMs = Long.MAX_VALUE,
            )

        assertEquals(Long.MAX_VALUE, result.maximumOutputBytes)
        assertTrue(result.isHonored)
    }
}
