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
    fun `lossless copy accepts source inherited AAC cadence jitter from the device case`() {
        val source = deviceCadenceSourceMetadata()
        val output = deviceCadenceOutputMetadata(source)

        assertEquals(
            output,
            AudioOutputVerifier.requireValidLosslessCopy(
                source = source,
                copy = copyResult(source, usesIndexedPhysicalSampleSizes = true),
                output = output,
            ),
        )
    }

    @Test
    fun `lossless copy rejects a cadence gap introduced beyond the source`() {
        val source = deviceCadenceSourceMetadata().copy(maxSampleDeltaUs = 21_334L)
        val output = deviceCadenceOutputMetadata(source).copy(maxSampleDeltaUs = 23_812L)

        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValidLosslessCopy(
                source = source,
                copy = copyResult(source, usesIndexedPhysicalSampleSizes = true),
                output = output,
            )
        }
    }

    @Test
    fun `lossless copy rejects a newly introduced full AAC frame gap`() {
        val source = deviceCadenceSourceMetadata()
        val output = deviceCadenceOutputMetadata(source).copy(maxSampleDeltaUs = 42_668L)

        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValidLosslessCopy(
                source = source,
                copy = copyResult(source, usesIndexedPhysicalSampleSizes = true),
                output = output,
            )
        }
    }

    @Test
    fun `transcode verification does not inherit a source cadence exception`() {
        val source = deviceCadenceSourceMetadata()
        val output = deviceCadenceOutputMetadata(source, fileName = "transcoded.m4a")

        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                output,
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.TRANSCODE_AAC_PROFILES,
                expectedSource = source,
            )
        }
    }

    @Test
    fun `expected source with regressing timestamps fails before output coverage comparison`() {
        val output = validMetadata().copy(sourceUri = "copy.m4a", fileName = "copy.m4a")
        assertThrows(IOException::class.java) {
            AudioOutputVerifier.requireValid(
                output,
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.COPY_AAC_PROFILES,
                expectedSource = validMetadata().copy(sampleTimesMonotonic = false),
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
    fun `indexed lossless payload requires exact source copy and output aggregates`() {
        val source = validMetadata().copy(usesIndexedPhysicalSampleSizes = true)
        val output = source.copy(sourceUri = "output.m4a", fileName = "output.m4a")
        val copy = copyResult(source, usesIndexedPhysicalSampleSizes = true)

        requireLosslessPayloadAggregateIntegrity(source, copy, output)

        listOf(
            copy.copy(sampleCount = copy.sampleCount - 1L),
            copy.copy(totalBytes = copy.totalBytes - 1L),
            copy.copy(usesIndexedPhysicalSampleSizes = false),
        ).forEach { invalid ->
            assertThrows(IOException::class.java) {
                requireLosslessPayloadAggregateIntegrity(source, invalid, output)
            }
        }
        assertThrows(IOException::class.java) {
            requireLosslessPayloadAggregateIntegrity(
                source,
                copy,
                output.copy(sampleBytes = output.sampleBytes - 1L),
            )
        }
    }

    @Test
    fun `legacy lossless payload fails closed unless all sentinel bounded aggregates agree`() {
        val source = validMetadata().copy(usesIndexedPhysicalSampleSizes = false)
        val copy = copyResult(source, usesIndexedPhysicalSampleSizes = false)
        val output = source.copy(sourceUri = "legacy-output.m4a", fileName = "legacy-output.m4a")

        requireLosslessPayloadAggregateIntegrity(source, copy, output)

        assertThrows(IOException::class.java) {
            requireLosslessPayloadAggregateIntegrity(
                source,
                copy,
                output.copy(sampleCount = output.sampleCount + 1L),
            )
        }
        assertThrows(IOException::class.java) {
            requireLosslessPayloadAggregateIntegrity(
                source.copy(usesIndexedPhysicalSampleSizes = true),
                copy,
                output,
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

    @Test
    fun `lossless integrity rejects equal aggregates when one sample byte changes`() {
        val originalDigest =
            AudioSampleDigest(
                AUDIO_SAMPLE_DIGEST_VERSION,
                "a4a65500d51c53df6c2bc87aeec5794a01fd8fc4f330ac7a04668c8ba1e5473c",
            )
        val changedDigest =
            AudioSampleDigest(
                AUDIO_SAMPLE_DIGEST_VERSION,
                "b6e6c51bc3e5a46cf1ced75186f281b20bd3d638f03c1c2abac3b60284335f79",
            )
        val source =
            validMetadata().copy(
                sampleCount = 2L,
                sampleBytes = 5L,
                usesIndexedPhysicalSampleSizes = true,
                sampleDigest = originalDigest,
            )
        val matchingCopy = copyResult(source, usesIndexedPhysicalSampleSizes = true)
        val matchingOutput = source.copy(sourceUri = "output.m4a", fileName = "output.m4a")

        requireLosslessPayloadAggregateIntegrity(source, matchingCopy, matchingOutput)

        val sourceCopyMismatch =
            assertThrows(IOException::class.java) {
                requireLosslessPayloadAggregateIntegrity(
                    source,
                    matchingCopy.copy(sampleDigest = changedDigest),
                    matchingOutput.copy(sampleDigest = changedDigest),
                )
            }
        assertTrue(sourceCopyMismatch.message.orEmpty().contains("Source payload"))
        assertFalse(sourceCopyMismatch.message.orEmpty().contains(originalDigest.sha256Hex))

        val copyOutputMismatch =
            assertThrows(IOException::class.java) {
                requireLosslessPayloadAggregateIntegrity(
                    source,
                    matchingCopy,
                    matchingOutput.copy(sampleDigest = changedDigest),
                )
            }
        assertTrue(copyOutputMismatch.message.orEmpty().contains("Muxed output"))
        assertFalse(copyOutputMismatch.message.orEmpty().contains(changedDigest.sha256Hex))
    }

    @Test
    fun `lossless integrity rejects unsupported digest version`() {
        val unsupportedDigest =
            AudioSampleDigest(
                version = 0x02,
                sha256Hex = "b".repeat(64),
            )
        val source =
            validMetadata().copy(
                usesIndexedPhysicalSampleSizes = true,
                sampleDigest = unsupportedDigest,
            )
        val copy = copyResult(source, usesIndexedPhysicalSampleSizes = true)
        val output = source.copy(sourceUri = "output.m4a", fileName = "output.m4a")

        assertThrows(IOException::class.java) {
            requireLosslessPayloadAggregateIntegrity(source, copy, output)
        }
    }

    private fun copyResult(
        metadata: AudioMetadata,
        usesIndexedPhysicalSampleSizes: Boolean,
    ) = EncodedAudioCopyResult(
        sampleCount = metadata.sampleCount,
        totalBytes = metadata.sampleBytes,
        firstInputTimeUs = requireNotNull(metadata.firstSampleTimeUs),
        lastInputTimeUs = requireNotNull(metadata.lastSampleTimeUs),
        lastOutputTimeUs =
            requireNotNull(metadata.lastSampleTimeUs) - requireNotNull(metadata.firstSampleTimeUs),
        usesIndexedPhysicalSampleSizes = usesIndexedPhysicalSampleSizes,
        sampleDigest = metadata.sampleDigest,
    )

    private fun deviceCadenceSourceMetadata() =
        AudioMetadata(
            sourceUri = "content://media/source/99",
            fileName = "device-source.mp4",
            fileSizeBytes = 26_586_782L,
            durationMs = 17_179L,
            container = "video/mp4",
            audioMime = AudioOutputVerifier.AAC_MIME,
            audioChannels = 1,
            audioSampleRate = 48_000,
            audioBitrate = 96_000,
            audioTrackCount = 1,
            videoTrackCount = 1,
            firstSampleTimeUs = 0L,
            lastSampleTimeUs = 17_158_041L,
            sampleCount = 805L,
            sampleBytes = 206_177L,
            sampleDigest = VALID_SAMPLE_DIGEST,
            usesIndexedPhysicalSampleSizes = true,
            sampleTimesMonotonic = true,
            maxSampleDeltaUs = 23_812L,
            audioProfile = AudioOutputVerifier.AAC_PROFILE_LC,
        )

    private fun deviceCadenceOutputMetadata(
        source: AudioMetadata,
        fileName: String = "copy.m4a",
    ) =
        source.copy(
            sourceUri = "content://media/output/100",
            fileName = fileName,
            fileSizeBytes = 220_000L,
            container = "audio/mp4",
            videoTrackCount = 0,
        )

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
            sampleDigest = VALID_SAMPLE_DIGEST,
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
            sampleDigest = VALID_SAMPLE_DIGEST,
            sampleTimesMonotonic = true,
            maxSampleDeltaUs = 21_337L,
            audioProfile = AudioOutputVerifier.AAC_PROFILE_LC,
        )

    private companion object {
        val VALID_SAMPLE_DIGEST =
            AudioSampleDigest(
                version = AUDIO_SAMPLE_DIGEST_VERSION,
                sha256Hex = "a".repeat(64),
            )
    }
}
