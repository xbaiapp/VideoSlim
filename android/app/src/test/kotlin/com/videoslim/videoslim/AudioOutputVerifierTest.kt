package com.videoslim.videoslim

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputVerifierTest {
    @Test
    fun `accepts one monotonic AAC LC audio track with real payload`() {
        val metadata = validMetadata()

        assertEquals(
            metadata,
            AudioOutputVerifier.requireValid(metadata, AudioOutputVerifier.AAC_MIME),
        )
    }

    @Test
    fun `copy admits only contractual AAC LC and HE profiles`() {
        listOf(
            AudioOutputVerifier.AAC_PROFILE_LC,
            AudioOutputVerifier.AAC_PROFILE_HE,
            AudioOutputVerifier.AAC_PROFILE_HE_PS,
        ).forEach { profile ->
            assertTrue(AudioOutputVerifier.isSupportedCopyProfile(profile))
            AudioOutputVerifier.requireValid(
                validMetadata().copy(audioProfile = profile),
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.COPY_AAC_PROFILES,
            )
        }
        listOf<Int?>(null, 1, 4, 42).forEach { profile ->
            assertFalse(AudioOutputVerifier.isSupportedCopyProfile(profile))
            assertThrows(IOException::class.java) {
                AudioOutputVerifier.requireValid(
                    validMetadata().copy(audioProfile = profile),
                    AudioOutputVerifier.AAC_MIME,
                    AudioOutputVerifier.COPY_AAC_PROFILES,
                )
            }
        }
    }

    @Test
    fun `forced AAC output contract accepts LC and rejects HE`() {
        AudioOutputVerifier.requireValid(
            validMetadata(),
            AudioOutputVerifier.AAC_MIME,
            AudioOutputVerifier.TRANSCODE_AAC_PROFILES,
        )
        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                validMetadata().copy(audioProfile = AudioOutputVerifier.AAC_PROFILE_HE),
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.TRANSCODE_AAC_PROFILES,
            )
        }
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
            validMetadata().copy(sampleBytes = 0L),
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
    fun `rejects plausible container duration with a truncated sample timeline`() {
        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                validMetadata().copy(lastSampleTimeUs = 10_000_000L, sampleCount = 470L),
                AudioOutputVerifier.AAC_MIME,
            )
        }
    }

    @Test
    fun `non AAC verification does not impose an AAC profile`() {
        val opus = validMetadata().copy(audioMime = "audio/opus", audioProfile = null)

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
            sampleBytes = 990_000L,
            sampleTimesMonotonic = true,
            audioProfile = AudioOutputVerifier.AAC_PROFILE_LC,
        )
}
