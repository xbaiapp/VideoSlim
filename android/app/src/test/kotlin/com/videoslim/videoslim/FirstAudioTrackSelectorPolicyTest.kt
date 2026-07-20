package com.videoslim.videoslim

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstAudioTrackSelectorPolicyTest {
    @Test
    fun `selects the first source audio group regardless of later bitrate candidates`() {
        val selected =
            firstAudioTrackCoordinate(
                rendererTypes = listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO),
                groupTrackCounts = listOf(listOf(1), listOf(1, 1, 1)),
            )

        assertEquals(AudioTrackCoordinate(rendererIndex = 1, groupIndex = 0, trackIndex = 0), selected)
    }

    @Test
    fun `does not fall through to a later audio group when the first exists`() {
        val selected =
            firstAudioTrackCoordinate(
                rendererTypes = listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_AUDIO),
                groupTrackCounts = listOf(listOf(1), listOf(1)),
            )

        assertEquals(AudioTrackCoordinate(rendererIndex = 0, groupIndex = 0, trackIndex = 0), selected)
    }

    @Test
    fun `returns no selection when no audio track exists`() {
        assertNull(
            firstAudioTrackCoordinate(
                rendererTypes = listOf(C.TRACK_TYPE_VIDEO),
                groupTrackCounts = listOf(listOf(1)),
            ),
        )
    }
}
