package com.videoslim.videoslim

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class TrimmedMediaItemTest {
    @Test
    fun `no trim keeps Media3 clipping unset`() {
        assertSame(MediaItem.ClippingConfiguration.UNSET, clippingConfigurationFor(null))
    }

    @Test
    fun `single segment maps exact endpoints without keyframe snapping`() {
        val clipping = clippingConfigurationFor(TimeTrim(startMs = 2_000L, endMs = 8_000L))

        assertEquals(2_000L, clipping.startPositionMs)
        assertEquals(8_000L, clipping.endPositionMs)
        assertFalse(clipping.startsAtKeyFrame)
        assertFalse(clipping.relativeToDefaultPosition)
        assertFalse(clipping.relativeToLiveWindow)
    }
}
