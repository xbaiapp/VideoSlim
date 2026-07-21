package com.videoslim.videoslim

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaExtractorSampleSourceTest {
    @Test
    fun `API 28 adapter exposes indexed sample size beside the physical read`() {
        val access = FakeAccess(sampleSize = 4L, payload = byteArrayOf(1, 2, 3))
        val source = MediaExtractorSampleSource(access, supportsIndexedSampleSize = true)
        val buffer = ByteBuffer.allocate(8)

        assertEquals(4L, source.indexedSampleSize)
        assertEquals(3, source.readSampleData(buffer))
        assertEquals(9_000L, source.sampleTimeUs)
        assertEquals(7, source.sampleFlags)
    }

    @Test
    fun `API 26 and 27 adapter withholds unavailable indexed sample size`() {
        val access = FakeAccess(sampleSize = 99L, payload = byteArrayOf(1, 2))
        val source = MediaExtractorSampleSource(access, supportsIndexedSampleSize = false)

        assertNull(source.indexedSampleSize)
        assertEquals(2, source.readSampleData(ByteBuffer.allocate(3)))
        assertEquals(false, source.advance())
    }

    private class FakeAccess(
        override val sampleSize: Long,
        private val payload: ByteArray,
    ) : MediaExtractorSampleAccess {
        override val sampleTime: Long = 9_000L
        override val sampleFlags: Int = 7

        override fun readSampleData(buffer: ByteBuffer): Int {
            buffer.put(payload)
            return payload.size
        }

        override fun advance(): Boolean = false
    }
}
