package com.videoslim.videoslim

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioMetadataScanPolicyTest {
    @Test
    fun `sample scan collects physically read timing evidence and detects regression`() {
        val times = listOf(1_000L, 2_000L, 1_500L)
        val sizes = listOf(100L, 200L, 300L)
        var index = 0

        val result =
            scanAudioSampleMetadata(
                sampleTimeUs = { times[index] },
                samplePayloadBytes = { sizes[index] },
                advance = {
                    index += 1
                    index < times.size
                },
                shouldCancel = { false },
            )

        assertEquals(1_000L, result.firstSampleTimeUs)
        assertEquals(1_500L, result.lastSampleTimeUs)
        assertEquals(3L, result.sampleCount)
        assertEquals(600L, result.sampleBytes)
        assertEquals(1_000L, result.maxSampleDeltaUs)
        assertFalse(result.monotonic)
    }

    @Test
    fun `sample scan rejects unreadable payload instead of trusting its index`() {
        assertThrows(IOException::class.java) {
            scanAudioSampleMetadata(
                sampleTimeUs = { 0L },
                samplePayloadBytes = { -1L },
                advance = { false },
                shouldCancel = { false },
            )
        }
    }

    @Test
    fun `complete physical sample read accepts a real bounded file payload`() {
        val file = File.createTempFile("videoslim-audio-sample", ".bin")
        file.writeBytes(ByteArray(257) { index -> (index and 0xff).toByte() })
        try {
            FileInputStream(file).channel.use { channel ->
                assertEquals(
                    257L,
                    readVerifiedAudioSamplePayload(
                        sampleBuffer = ByteBuffer.allocate(512),
                        indexedSampleSize = 257L,
                        readSampleData = { buffer -> channel.read(buffer) },
                    ),
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `indexed but physically truncated real sample is rejected`() {
        val file = File.createTempFile("videoslim-truncated-audio-sample", ".bin")
        file.writeBytes(ByteArray(127) { 0x5a })
        try {
            FileInputStream(file).channel.use { channel ->
                assertThrows(IOException::class.java) {
                    readVerifiedAudioSamplePayload(
                        sampleBuffer = ByteBuffer.allocate(512),
                        indexedSampleSize = 256L,
                        readSampleData = { buffer -> channel.read(buffer) },
                    )
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `all API policies reject failed zero short and over-cap physical reads`() {
        val buffer = ByteBuffer.allocate(32)
        listOf(-1, 0).forEach { failedRead ->
            assertThrows(IOException::class.java) {
                readVerifiedAudioSamplePayload(buffer, null) { failedRead }
            }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(buffer, 16L) { 15 }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(buffer, 16L) { 17 }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(
                buffer,
                MAX_AUDIO_SAMPLE_BUFFER_BYTES.toLong() + 1L,
            ) { 1 }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(buffer, null) {
                MAX_AUDIO_SAMPLE_BUFFER_BYTES + 1
            }
        }
    }

    @Test
    fun `legacy API policy accepts a successful bounded physical read without index metadata`() {
        assertEquals(
            24L,
            readVerifiedAudioSamplePayload(ByteBuffer.allocate(32), null) { 24 },
        )
    }

    @Test
    fun `sample scan cooperatively cancels between samples`() {
        val times = listOf(1_000L, 2_000L, 3_000L)
        var index = 0
        var payloadReads = 0

        assertThrows(CancellationException::class.java) {
            scanAudioSampleMetadata(
                sampleTimeUs = { times[index] },
                samplePayloadBytes = {
                    payloadReads += 1
                    128L
                },
                advance = {
                    index += 1
                    index < times.size
                },
                shouldCancel = { index >= 1 },
            )
        }

        assertEquals(1, payloadReads)
    }
}
