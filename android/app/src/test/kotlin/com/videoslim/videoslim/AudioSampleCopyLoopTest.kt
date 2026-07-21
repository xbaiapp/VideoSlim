package com.videoslim.videoslim

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSampleCopyLoopTest {
    @Test
    fun `copy loop rejects regressing source timestamps instead of rewriting them`() {
        val source =
            FakeSource(
                listOf(
                    Sample(byteArrayOf(1, 2), 1_000_000L, 1),
                    Sample(byteArrayOf(3), 1_021_333L, 0),
                    Sample(byteArrayOf(4, 5, 6), 1_020_000L, 0),
                ),
            )
        val sink = FakeSink()

        assertThrows(IOException::class.java) {
            copyEncodedAudioSamples(
                source = source,
                sink = sink,
                durationUs = 2_000_000L,
                requestedBufferBytes = 32,
                shouldCancel = { false },
            )
        }

        assertEquals(listOf(0L, 21_333L), sink.samples.map { it.presentationTimeUs })
        assertArrayEquals(byteArrayOf(1, 2), sink.payloads.first())
    }

    @Test
    fun `copy loop preserves strictly monotonic source timestamp deltas exactly`() {
        val sink = FakeSink()
        val result =
            copyEncodedAudioSamples(
                source =
                    FakeSource(
                        listOf(
                            Sample(byteArrayOf(1), 1_000_000L, 1),
                            Sample(byteArrayOf(2), 1_021_333L, 0),
                            Sample(byteArrayOf(3), 1_042_667L, 0),
                        ),
                    ),
                sink = sink,
                durationUs = 50_000L,
                requestedBufferBytes = 8,
                shouldCancel = { false },
            )

        assertEquals(listOf(0L, 21_333L, 42_667L), sink.samples.map { it.presentationTimeUs })
        assertEquals(3L, result.sampleCount)
        assertEquals(3L, result.totalBytes)
    }

    @Test
    fun `copy digest matches exact sink payloads without changing sink ByteBuffer window`() {
        val sink = FakeSink()
        val result =
            copyEncodedAudioSamples(
                source =
                    FakeSource(
                        listOf(
                            Sample(byteArrayOf(0x00, 0x01, 0xff.toByte()), 1_000L, 7),
                            Sample("ab".toByteArray(), 2_000L, 9),
                        ),
                    ),
                sink = sink,
                durationUs = 2_000L,
                requestedBufferBytes = 8,
                shouldCancel = { false },
            )

        assertEquals(
            "a4a65500d51c53df6c2bc87aeec5794a01fd8fc4f330ac7a04668c8ba1e5473c",
            result.sampleDigest.sha256Hex,
        )
        assertEquals(AUDIO_SAMPLE_DIGEST_VERSION, result.sampleDigest.version)
        assertEquals(listOf(0, 0), sink.bufferPositionsAtWrite)
        assertEquals(listOf(3, 2), sink.bufferLimitsAtWrite)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0xff.toByte()), sink.payloads[0])
        assertArrayEquals("ab".toByteArray(), sink.payloads[1])
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
    fun `copy loop has a hard buffer cap and cancels before a write`() {
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
        assertEquals(0, sink.samples.size)
    }

    @Test
    fun `zero byte sample fails closed before any timestamp can be rewritten`() {
        val progress = mutableListOf<Double>()
        val sink = FakeSink()

        assertThrows(IOException::class.java) {
            copyEncodedAudioSamples(
                source =
                    FakeSource(
                        listOf(
                            Sample(byteArrayOf(), 500L, 0),
                            Sample(byteArrayOf(1), 2_000L, 0),
                        ),
                    ),
                sink = sink,
                durationUs = 4_000L,
                requestedBufferBytes = 8,
                shouldCancel = { false },
                onProgress = { progress += it },
            )
        }

        assertTrue(sink.samples.isEmpty())
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `indexed copy rejects zero negative short over-index and over-cap physical reads`() {
        fun rejects(sample: Sample, requestedBufferBytes: Int = 8) {
            assertThrows(IOException::class.java) {
                copyEncodedAudioSamples(
                    source = FakeSource(listOf(sample)),
                    sink = FakeSink(),
                    durationUs = 1_000L,
                    requestedBufferBytes = requestedBufferBytes,
                    shouldCancel = { false },
                )
            }
        }

        rejects(Sample(byteArrayOf(1), 0L, 0, indexedSize = 0L))
        rejects(Sample(byteArrayOf(1), 0L, 0, indexedSize = -1L))
        rejects(Sample(byteArrayOf(), 0L, 0, indexedSize = 1L, readResult = 0))
        rejects(Sample(byteArrayOf(), 0L, 0, indexedSize = 1L, readResult = -1))
        rejects(Sample(byteArrayOf(1, 2), 0L, 0, indexedSize = 3L))
        rejects(Sample(byteArrayOf(1, 2, 3), 0L, 0, indexedSize = 2L))
        rejects(Sample(ByteArray(9), 0L, 0, indexedSize = 9L))
    }

    @Test
    fun `legacy copy uses a sentinel byte and rejects over-bound buffer filling ambiguity`() {
        assertThrows(IOException::class.java) {
            copyEncodedAudioSamples(
                source = FakeSource(listOf(Sample(ByteArray(9), 0L, 0, indexedSize = null))),
                sink = FakeSink(),
                durationUs = 1_000L,
                requestedBufferBytes = 8,
                shouldCancel = { false },
            )
        }

        val accepted =
            copyEncodedAudioSamples(
                source = FakeSource(listOf(Sample(ByteArray(8), 0L, 0, indexedSize = null))),
                sink = FakeSink(),
                durationUs = 1_000L,
                requestedBufferBytes = 8,
                shouldCancel = { false },
            )
        assertEquals(8L, accepted.totalBytes)
        assertEquals(false, accepted.usesIndexedPhysicalSampleSizes)
    }

    @Test
    fun `copy rejects a stream that mixes indexed and legacy size evidence`() {
        assertThrows(IOException::class.java) {
            copyEncodedAudioSamples(
                source =
                    FakeSource(
                        listOf(
                            Sample(byteArrayOf(1), 0L, 0, indexedSize = 1L),
                            Sample(byteArrayOf(2), 1_000L, 0, indexedSize = null),
                        ),
                    ),
                sink = FakeSink(),
                durationUs = 2_000L,
                requestedBufferBytes = 8,
                shouldCancel = { false },
            )
        }
    }

    private data class Sample(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
        val indexedSize: Long? = bytes.size.toLong(),
        val readResult: Int = bytes.size,
    )

    private class FakeSource(
        private val samples: List<Sample>,
    ) : EncodedAudioSampleSource {
        private var index = 0

        override val sampleTimeUs: Long
            get() = samples.getOrNull(index)?.presentationTimeUs ?: -1L
        override val sampleFlags: Int
            get() = samples.getOrNull(index)?.flags ?: 0
        override val indexedSampleSize: Long?
            get() = samples.getOrNull(index)?.indexedSize

        override fun readSampleData(buffer: ByteBuffer): Int {
            val sample = samples.getOrNull(index) ?: return -1
            if (sample.readResult > 0) buffer.put(sample.bytes)
            return sample.readResult
        }

        override fun advance(): Boolean {
            index += 1
            return index < samples.size
        }
    }

    private class FakeSink : EncodedAudioSampleSink {
        val samples = mutableListOf<EncodedAudioSampleInfo>()
        val payloads = mutableListOf<ByteArray>()
        val bufferPositionsAtWrite = mutableListOf<Int>()
        val bufferLimitsAtWrite = mutableListOf<Int>()

        override fun writeSampleData(
            buffer: ByteBuffer,
            info: EncodedAudioSampleInfo,
        ) {
            samples += info
            bufferPositionsAtWrite += buffer.position()
            bufferLimitsAtWrite += buffer.limit()
            val payload = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(payload)
            payloads += payload
        }
    }
}
