package com.videoslim.videoslim

import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSampleCopyLoopTest {
    @Test
    fun `copy loop rebases timestamps and makes regressions strictly monotonic`() {
        val source =
            FakeSource(
                listOf(
                    Sample(byteArrayOf(1, 2), 1_000_000L, 1),
                    Sample(byteArrayOf(3), 1_021_333L, 0),
                    Sample(byteArrayOf(4, 5, 6), 1_020_000L, 0),
                ),
            )
        val sink = FakeSink()

        val result =
            copyEncodedAudioSamples(
                source = source,
                sink = sink,
                durationUs = 2_000_000L,
                requestedBufferBytes = 32,
                shouldCancel = { false },
            )

        assertEquals(listOf(0L, 21_333L, 21_334L), sink.samples.map { it.presentationTimeUs })
        assertEquals(3L, result.sampleCount)
        assertEquals(6L, result.totalBytes)
        assertArrayEquals(byteArrayOf(1, 2), sink.payloads.first())
    }

    @Test
    fun `progress is bounded and completes after samples are copied`() {
        val progress = mutableListOf<Double>()
        val source = FakeSource(listOf(Sample(byteArrayOf(1), 5_000L, 0)))

        copyEncodedAudioSamples(
            source = source,
            sink = FakeSink(),
            durationUs = 10_000L,
            requestedBufferBytes = 8,
            shouldCancel = { false },
            onProgress = { progress += it },
        )

        assertTrue(progress.all { it in 0.0..1.0 })
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test
    fun `copy loop has a hard buffer cap and cancels before another write`() {
        assertThrows(IllegalArgumentException::class.java) {
            boundedAudioSampleBufferSize(MAX_AUDIO_SAMPLE_BUFFER_BYTES + 1)
        }
        assertEquals(DEFAULT_AUDIO_SAMPLE_BUFFER_BYTES, boundedAudioSampleBufferSize(null))

        var checks = 0
        val sink = FakeSink()
        assertThrows(CancellationException::class.java) {
            copyEncodedAudioSamples(
                source =
                    FakeSource(
                        listOf(
                            Sample(byteArrayOf(1), 0L, 0),
                            Sample(byteArrayOf(2), 1_000L, 0),
                        ),
                    ),
                sink = sink,
                durationUs = 2_000L,
                requestedBufferBytes = 8,
                shouldCancel = { ++checks > 1 },
            )
        }
        assertEquals(1, sink.samples.size)
    }

    private data class Sample(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private class FakeSource(
        private val samples: List<Sample>,
    ) : EncodedAudioSampleSource {
        private var index = 0

        override val sampleTimeUs: Long
            get() = samples.getOrNull(index)?.presentationTimeUs ?: -1L
        override val sampleFlags: Int
            get() = samples.getOrNull(index)?.flags ?: 0

        override fun readSampleData(buffer: ByteBuffer): Int {
            val sample = samples.getOrNull(index) ?: return -1
            buffer.put(sample.bytes)
            return sample.bytes.size
        }

        override fun advance(): Boolean {
            index += 1
            return index < samples.size
        }
    }

    private class FakeSink : EncodedAudioSampleSink {
        val samples = mutableListOf<EncodedAudioSampleInfo>()
        val payloads = mutableListOf<ByteArray>()

        override fun writeSampleData(
            buffer: ByteBuffer,
            info: EncodedAudioSampleInfo,
        ) {
            samples += info
            val payload = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(payload)
            payloads += payload
        }
    }
}
