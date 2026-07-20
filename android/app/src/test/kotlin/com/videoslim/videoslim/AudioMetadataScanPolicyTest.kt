package com.videoslim.videoslim

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioMetadataScanPolicyTest {
    @Test
    fun `sample scan collects full timing evidence and detects regression`() {
        val times = listOf(1_000L, 2_000L, 1_500L)
        val sizes = listOf(100L, 200L, 300L)
        var index = 0

        val result =
            scanAudioSampleMetadata(
                sampleTimeUs = { times[index] },
                sampleSizeBytes = { sizes[index] },
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
        assertFalse(result.monotonic)
    }

    @Test
    fun `sample scan cooperatively cancels between samples`() {
        val times = listOf(1_000L, 2_000L, 3_000L)
        var index = 0
        var sizeReads = 0

        assertThrows(CancellationException::class.java) {
            scanAudioSampleMetadata(
                sampleTimeUs = { times[index] },
                sampleSizeBytes = {
                    sizeReads += 1
                    128L
                },
                advance = {
                    index += 1
                    index < times.size
                },
                shouldCancel = { index >= 1 },
            )
        }

        assertEquals(1, sizeReads)
    }
}
