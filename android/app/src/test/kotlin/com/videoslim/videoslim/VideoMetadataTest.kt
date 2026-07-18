package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoMetadataTest {
    @Test
    fun `channel map matches the Dart VideoInfo wire contract`() {
        val metadata = sampleMetadata()

        assertEquals(
            listOf(
                "uri",
                "fileName",
                "fileSizeBytes",
                "durationMs",
                "container",
                "videoCodec",
                "width",
                "height",
                "rotationDegrees",
                "frameRate",
                "videoBitrate",
                "audioCodec",
                "audioChannels",
                "audioSampleRate",
                "audioBitrate",
                "isHdr",
            ),
            metadata.toChannelMap().keys.toList(),
        )
    }

    @Test
    fun `channel map exposes display geometry and keeps storage geometry internal`() {
        val channelMap = sampleMetadata().toChannelMap()

        assertEquals(1080, channelMap["width"])
        assertEquals(1920, channelMap["height"])
        assertEquals(90, channelMap["rotationDegrees"])
        assertFalse(channelMap.containsKey("storageWidth"))
        assertFalse(channelMap.containsKey("storageHeight"))
        assertEquals(null, channelMap["audioCodec"])
        assertEquals(null, channelMap["audioChannels"])
    }

    private fun sampleMetadata() =
        VideoMetadata(
            sourceUri = "content://media/video/1",
            fileName = "sample.mp4",
            fileSizeBytes = 1_024L,
            durationMs = 2_000L,
            container = "video/mp4",
            videoMime = "video/avc",
            storageWidth = 1920,
            storageHeight = 1080,
            displayWidth = 1080,
            displayHeight = 1920,
            rotationDegrees = 90,
            frameRate = 30.0,
            videoBitrate = 4_000_000,
            audioMime = null,
            audioChannels = null,
            audioSampleRate = null,
            audioBitrate = null,
            isHdr = true,
        )
}
