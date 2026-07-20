package com.videoslim.videoslim

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputVerifierTest {
    @Test
    fun `accepts one monotonic AAC LC audio track with physically consistent payload`() {
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
    fun `rejects empty physically inconsistent multi-track or wrong-codec outputs`() {
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
            validMetadata().copy(sampleBytes = 1_000_001L),
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
    fun `frame-aware declared duration rejects a one-frame near-total short truncation`() {
        val oneFrameOfHalfSecond =
            shortValidMetadata().copy(
                lastSampleTimeUs = 0L,
                sampleCount = 1L,
                sampleBytes = 400L,
                maxSampleDeltaUs = null,
            )

        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                oneFrameOfHalfSecond,
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.COPY_AAC_PROFILES,
            )
        }
    }

    @Test
    fun `frame-aware declared duration rejects sparse indexed samples in a short clip`() {
        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                shortValidMetadata().copy(
                    lastSampleTimeUs = 436_000L,
                    sampleCount = 2L,
                    sampleBytes = 800L,
                    maxSampleDeltaUs = 436_000L,
                ),
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.COPY_AAC_PROFILES,
            )
        }
    }

    @Test
    fun `frame-aware verification rejects a hidden internal sparse gap`() {
        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                shortValidMetadata().copy(
                    lastSampleTimeUs = 458_000L,
                    sampleCount = 22L,
                    sampleBytes = 7_000L,
                    maxSampleDeltaUs = 457_980L,
                ),
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.COPY_AAC_PROFILES,
            )
        }
    }

    @Test
    fun `short lossless copy accepts AAC frame rounding but rejects source-relative truncation`() {
        val source = shortValidMetadata()
        val completeCopy = source.copy(sourceUri = "copy.m4a", fileName = "copy.m4a")
        AudioOutputVerifier.requireValid(
            completeCopy,
            AudioOutputVerifier.AAC_MIME,
            AudioOutputVerifier.COPY_AAC_PROFILES,
            expectedSource = source,
        )

        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                completeCopy.copy(
                    lastSampleTimeUs = 0L,
                    sampleCount = 1L,
                    sampleBytes = 400L,
                    maxSampleDeltaUs = null,
                ),
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.COPY_AAC_PROFILES,
                expectedSource = source,
            )
        }
    }

    @Test
    fun `short AAC transcode accepts encoder frame rounding but rejects source-relative truncation`() {
        val source =
            shortValidMetadata().copy(
                audioMime = "audio/opus",
                audioProfile = null,
                sampleCount = 26L,
                lastSampleTimeUs = 480_000L,
                maxSampleDeltaUs = 19_200L,
            )
        val completeTranscode =
            shortValidMetadata().copy(
                lastSampleTimeUs = 469_333L,
                sampleCount = 23L,
                maxSampleDeltaUs = 21_334L,
            )
        AudioOutputVerifier.requireValid(
            completeTranscode,
            AudioOutputVerifier.AAC_MIME,
            AudioOutputVerifier.TRANSCODE_AAC_PROFILES,
            expectedSource = source,
        )

        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                completeTranscode.copy(
                    lastSampleTimeUs = 0L,
                    sampleCount = 1L,
                    sampleBytes = 400L,
                    maxSampleDeltaUs = null,
                ),
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.TRANSCODE_AAC_PROFILES,
                expectedSource = source,
            )
        }
    }

    @Test
    fun `rejects plausible long container duration with a truncated sample timeline`() {
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

    private fun shortValidMetadata() =
        AudioMetadata(
            sourceUri = "content://media/external/audio/media/7",
            fileName = "short.m4a",
            fileSizeBytes = 12_000L,
            durationMs = 500L,
            container = "audio/mp4",
            audioMime = AudioOutputVerifier.AAC_MIME,
            audioChannels = 2,
            audioSampleRate = 48_000,
            audioBitrate = 128_000,
            audioTrackCount = 1,
            videoTrackCount = 0,
            firstSampleTimeUs = 0L,
            lastSampleTimeUs = 490_667L,
            sampleCount = 24L,
            sampleBytes = 8_000L,
            sampleTimesMonotonic = true,
            maxSampleDeltaUs = 21_334L,
            audioProfile = AudioOutputVerifier.AAC_PROFILE_LC,
        )

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
            maxSampleDeltaUs = 21_337L,
            audioProfile = AudioOutputVerifier.AAC_PROFILE_LC,
        )
}
