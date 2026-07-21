package com.videoslim.videoslim

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSampleDigestTest {
    @Test
    fun `canonical v1 stream has deterministic digest`() {
        val digest = digestOf(byteArrayOf(0x00, 0x01, 0xff.toByte()), "ab".toByteArray())

        assertEquals(AUDIO_SAMPLE_DIGEST_VERSION, digest.version)
        assertEquals(
            "a4a65500d51c53df6c2bc87aeec5794a01fd8fc4f330ac7a04668c8ba1e5473c",
            digest.sha256Hex,
        )
    }

    @Test
    fun `equal sample counts and lengths with one changed byte have different digest`() {
        val original = digestOf(byteArrayOf(0x00, 0x01, 0xff.toByte()), "ab".toByteArray())
        val changed = digestOf(byteArrayOf(0x00, 0x01, 0xff.toByte()), "ac".toByteArray())

        assertEquals(
            "b6e6c51bc3e5a46cf1ced75186f281b20bd3d638f03c1c2abac3b60284335f79",
            changed.sha256Hex,
        )
        assertNotEquals(original, changed)
    }

    @Test
    fun `sample order and digest version are committed`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)

        assertNotEquals(digestOf(first, second), digestOf(second, first))
        assertNotEquals(
            digestOf(first, second),
            digestOf(first, second, version = 0x02),
        )
    }

    @Test
    fun `heap direct sliced and read-only ranges hash identically without mutating views`() {
        val heap = ByteBuffer.wrap(byteArrayOf(0x55, 0x00, 0x01, 0xff.toByte(), 0x66))
        val direct = ByteBuffer.allocateDirect(5).apply { put(byteArrayOf(0x55, 0x00, 0x01, 0xff.toByte(), 0x66)) }
        val sliced =
            ByteBuffer.wrap(byteArrayOf(0x70, 0x55, 0x00, 0x01, 0xff.toByte(), 0x66, 0x71)).apply {
                position(1)
                limit(6)
            }.slice()
        val readOnly = heap.asReadOnlyBuffer()
        val inputs = listOf(heap, direct, sliced, readOnly)
        val digests =
            inputs.map { buffer ->
                buffer.position(2)
                buffer.limit(4)
                val position = buffer.position()
                val limit = buffer.limit()
                val accumulator = AudioSampleDigestAccumulator()

                accumulator.addSample(buffer, offset = 1, length = 3)
                val digest = accumulator.finish()

                assertEquals(position, buffer.position())
                assertEquals(limit, buffer.limit())
                digest
            }

        assertEquals(1, digests.toSet().size)
        val changedSentinels = ByteBuffer.wrap(byteArrayOf(0x12, 0x00, 0x01, 0xff.toByte(), 0x34))
        assertEquals(
            digests.first(),
            AudioSampleDigestAccumulator().apply {
                addSample(changedSentinels, offset = 1, length = 3)
            }.finish(),
        )
    }

    @Test
    fun `accumulator rejects invalid ranges and repeated finish`() {
        val accumulator = AudioSampleDigestAccumulator()
        val buffer = ByteBuffer.allocate(4)

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.addSample(buffer, offset = 0, length = 0)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            accumulator.addSample(buffer, offset = 2, length = 3)
        }
        accumulator.addSample(buffer, offset = 0, length = 1)
        accumulator.finish()
        assertThrows(IllegalStateException::class.java) { accumulator.finish() }
        assertThrows(IllegalStateException::class.java) {
            accumulator.addSample(buffer, offset = 0, length = 1)
        }
    }

    private fun digestOf(
        vararg samples: ByteArray,
        version: Int = AUDIO_SAMPLE_DIGEST_VERSION,
    ): AudioSampleDigest {
        val accumulator = AudioSampleDigestAccumulator(version)
        samples.forEach { sample -> accumulator.addSample(ByteBuffer.wrap(sample), 0, sample.size) }
        return accumulator.finish()
    }
}
