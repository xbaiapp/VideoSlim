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
                samplePayloadBytes = { consume ->
                    val payload = ByteArray(sizes[index].toInt()) { index.toByte() }
                    consume(ByteBuffer.wrap(payload), 0, payload.size)
                    payload.size.toLong()
                },
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
                        consumeVerifiedPayload = { _, _, _ -> },
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
                        consumeVerifiedPayload = { _, _, _ -> },
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
                readVerifiedAudioSamplePayload(
                    sampleBuffer = buffer,
                    indexedSampleSize = null,
                    readSampleData = { failedRead },
                    consumeVerifiedPayload = { _, _, _ -> },
                )
            }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(buffer, 16L, { 15 }) { _, _, _ -> }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(buffer, 16L, { 17 }) { _, _, _ -> }
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(
                buffer,
                MAX_AUDIO_SAMPLE_BUFFER_BYTES.toLong() + 1L,
                readSampleData = { 1 },
                consumeVerifiedPayload = { _, _, _ -> },
            )
        }
        assertThrows(IOException::class.java) {
            readVerifiedAudioSamplePayload(
                sampleBuffer = buffer,
                indexedSampleSize = null,
                readSampleData = { MAX_AUDIO_SAMPLE_BUFFER_BYTES + 1 },
                consumeVerifiedPayload = { _, _, _ -> },
            )
        }
    }

    @Test
    fun `legacy API policy accepts a successful bounded physical read without index metadata`() {
        assertEquals(
            24L,
            readVerifiedAudioSamplePayload(
                sampleBuffer = ByteBuffer.allocate(32),
                indexedSampleSize = null,
                readSampleData = { 24 },
                consumeVerifiedPayload = { _, _, _ -> },
            ),
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
                samplePayloadBytes = { consume ->
                    payloadReads += 1
                    val payload = ByteBuffer.wrap(ByteArray(128))
                    consume(payload, 0, 128)
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

    @Test
    fun `source and output rescans digest the canonical physically read payload stream`() {
        val samples =
            listOf(
                byteArrayOf(0x00, 0x01, 0xff.toByte()),
                "ab".toByteArray(),
            )

        val sourceScan = scanVerifiedPayloads(samples, usesIndexedSizes = true)
        val outputRescan = scanVerifiedPayloads(samples, usesIndexedSizes = true)

        assertEquals(
            "a4a65500d51c53df6c2bc87aeec5794a01fd8fc4f330ac7a04668c8ba1e5473c",
            sourceScan.sampleDigest.sha256Hex,
        )
        assertEquals(sourceScan.sampleDigest, outputRescan.sampleDigest)
    }

    @Test
    fun `indexed and legacy physical read policies produce the same sample digest`() {
        val samples = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))

        assertEquals(
            scanVerifiedPayloads(samples, usesIndexedSizes = true).sampleDigest,
            scanVerifiedPayloads(samples, usesIndexedSizes = false).sampleDigest,
        )
    }

    @Test
    fun `sample scan requires exactly one matching verified payload delivery`() {
        fun assertRejected(samplePayloadBytes: (VerifiedPayloadConsumer) -> Long) {
            assertThrows(IOException::class.java) {
                scanAudioSampleMetadata(
                    sampleTimeUs = { 0L },
                    samplePayloadBytes = samplePayloadBytes,
                    advance = { false },
                    shouldCancel = { false },
                )
            }
        }

        assertRejected { 1L }
        assertRejected { consume ->
            consume(ByteBuffer.wrap(byteArrayOf(1)), 0, 1)
            2L
        }
        assertRejected { consume ->
            val payload = ByteBuffer.wrap(byteArrayOf(1))
            consume(payload, 0, 1)
            consume(payload, 0, 1)
            1L
        }
    }

    private fun scanVerifiedPayloads(
        samples: List<ByteArray>,
        usesIndexedSizes: Boolean,
    ): AudioSampleTiming {
        var index = 0
        val reusableBuffer = ByteBuffer.allocate(64)
        return scanAudioSampleMetadata(
            sampleTimeUs = { index * 1_000L },
            samplePayloadBytes = { consume ->
                val sample = samples[index]
                readVerifiedAudioSamplePayload(
                    sampleBuffer = reusableBuffer,
                    indexedSampleSize = sample.size.toLong().takeIf { usesIndexedSizes },
                    readSampleData = { buffer ->
                        buffer.put(sample)
                        sample.size
                    },
                    consumeVerifiedPayload = consume,
                )
            },
            advance = {
                index += 1
                index < samples.size
            },
            shouldCancel = { false },
        )
    }
}
