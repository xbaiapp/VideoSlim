package com.videoslim.videoslim

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioOutputVerifierTest {
    @Test
    fun `accepts one monotonic AAC audio track without video`() {
        val metadata = validMetadata()

        assertEquals(
            metadata,
            AudioOutputVerifier.requireValid(metadata, AudioOutputVerifier.AAC_MIME),
        )
    }

    @Test
    fun `rejects empty multi-track video-bearing or wrong-codec outputs`() {
        listOf(
            validMetadata().copy(fileSizeBytes = 0L),
            validMetadata().copy(durationMs = 0L),
            validMetadata().copy(audioTrackCount = 2),
            validMetadata().copy(videoTrackCount = 1),
            validMetadata().copy(audioMime = "audio/opus"),
            validMetadata().copy(audioChannels = 6),
            validMetadata().copy(audioSampleRate = 0),
            validMetadata().copy(sampleCount = 0L),
            validMetadata().copy(sampleTimesMonotonic = false),
            validMetadata().copy(firstSampleTimeUs = 100_001L),
            validMetadata().copy(lastSampleTimeUs = -1L),
        ).forEach { metadata ->
            assertThrows(IOException::class.java) {
                AudioOutputVerifier.requireValid(metadata, AudioOutputVerifier.AAC_MIME)
            }
        }
    }

    @Test
    fun `copy verification can preserve non-AAC only when no required MIME is supplied`() {
        val opus = validMetadata().copy(audioMime = "audio/opus")

        assertEquals(opus, AudioOutputVerifier.requireValid(opus))
        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(opus, AudioOutputVerifier.AAC_MIME)
        }
    }

    private fun validMetadata() =
        AudioMetadata(
            sourceUri = "content://media/external/audio/media/42",
            fileName = "lecture.m4a",
            fileSizeBytes = 1_000_000L,
            durationMs = 60_000L,
            container = "audio/mp4",
            audioMime = AudioOutputVerifier.AAC_MIME,
            audioChannels = 2,
            audioSampleRate = 48_000,
            audioBitrate = 128_000,
            audioTrackCount = 1,
            videoTrackCount = 0,
            firstSampleTimeUs = 0L,
            lastSampleTimeUs = 59_978_000L,
            sampleCount = 2_812L,
            sampleTimesMonotonic = true,
        )
}
