package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioExtractPlanTest {
    @Test
    fun `copy estimate uses known source audio bitrate with a 120 percent upper bound`() {
        val plan =
            AudioExtractPlan.create(
                request = request(mode = AudioExtractMode.COPY),
                durationUs = 10_000_000L,
                sourceAudioBitrate = 128_000,
                storageTopology = unlimitedTopology(),
            )

        assertEquals(128_000, plan.estimateBitrateBps)
        assertFalse(plan.usedConservativeUnknownCopyBitrate)
        assertEquals(160_000L, plan.storageEstimate.audioBytes)
        assertEquals(192_000L, plan.storageEstimate.upperAudioBytes)
        assertEquals(4L * 1_024L * 1_024L, plan.storageEstimate.overheadBytes)
        assertEquals(
            160_000L + 4L * 1_024L * 1_024L,
            plan.storageEstimate.estimatedMinBytes,
        )
        assertEquals(
            192_000L + 4L * 1_024L * 1_024L,
            plan.storageEstimate.estimatedMaxBytes,
        )
        assertTrue(plan.hasSufficientStorage)
    }

    @Test
    fun `copy with unknown bitrate uses 512 kbps and never source video bytes`() {
        val plan =
            AudioExtractPlan.create(
                request = request(mode = AudioExtractMode.COPY),
                durationUs = 10_000_000L,
                sourceAudioBitrate = null,
                storageTopology = unlimitedTopology(),
            )

        assertEquals(CONSERVATIVE_UNKNOWN_COPY_BITRATE_BPS, plan.estimateBitrateBps)
        assertTrue(plan.usedConservativeUnknownCopyBitrate)
        assertEquals(640_000L, plan.storageEstimate.audioBytes)
        assertEquals(768_000L, plan.storageEstimate.upperAudioBytes)
        assertEquals(4_962_304L, plan.storageEstimate.estimatedMaxBytes)
        assertTrue(plan.storageEstimate.estimatedMaxBytes < 10_000_000L)
    }

    @Test
    fun `non-positive copy bitrates are treated as unknown metadata`() {
        listOf(0, -1).forEach { sourceBitrate ->
            val plan =
                AudioExtractPlan.create(
                    request = request(mode = AudioExtractMode.COPY),
                    durationUs = 1_000_000L,
                    sourceAudioBitrate = sourceBitrate,
                    storageTopology = unlimitedTopology(),
                )
            assertEquals(CONSERVATIVE_UNKNOWN_COPY_BITRATE_BPS, plan.estimateBitrateBps)
            assertTrue(plan.usedConservativeUnknownCopyBitrate)
        }
    }

    @Test
    fun `AAC estimates use each requested bitrate and remain monotonic`() {
        val bitrates = listOf(64_000, 96_000, 128_000, 192_000)
        val plans =
            bitrates.map { bitrate ->
                AudioExtractPlan.create(
                    request = request(mode = AudioExtractMode.AAC, bitrate = bitrate),
                    durationUs = 60_000_000L,
                    sourceAudioBitrate = Int.MAX_VALUE,
                    storageTopology = unlimitedTopology(),
                )
            }

        assertEquals(bitrates, plans.map(AudioExtractPlan::estimateBitrateBps))
        assertTrue(plans.none(AudioExtractPlan::usedConservativeUnknownCopyBitrate))
        assertEquals(
            plans.map { it.storageEstimate.estimatedMaxBytes }.sorted(),
            plans.map { it.storageEstimate.estimatedMaxBytes },
        )
        assertTrue(
            plans.zipWithNext().all { (lower, higher) ->
                lower.storageEstimate.estimatedMaxBytes < higher.storageEstimate.estimatedMaxBytes
            },
        )
    }

    @Test
    fun `space gate covers overlapping temp and public outputs with existing headroom`() {
        val estimate =
            AudioExtractPlan.create(
                request = request(AudioExtractMode.COPY),
                durationUs = 10_000_000L,
                sourceAudioBitrate = null,
                storageTopology = unlimitedTopology(),
            ).storageEstimate

        val headroom = 64L * 1_024L * 1_024L
        assertEquals(estimate.estimatedMaxBytes + headroom, estimate.cacheRequiredBytes)
        assertEquals(estimate.estimatedMaxBytes + headroom, estimate.publicRequiredBytes)
        assertEquals(estimate.estimatedMaxBytes * 2L + headroom, estimate.sharedPoolRequiredBytes)

        assertTrue(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = estimate.cacheRequiredBytes,
                    publicAvailableBytes = estimate.publicRequiredBytes,
                    sharesStoragePool = false,
                ),
            ).hasSufficientStorage,
        )
        assertFalse(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = estimate.cacheRequiredBytes,
                    publicAvailableBytes = estimate.publicRequiredBytes,
                    sharesStoragePool = true,
                ),
            ).hasSufficientStorage,
        )
        assertTrue(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = estimate.sharedPoolRequiredBytes,
                    publicAvailableBytes = estimate.sharedPoolRequiredBytes,
                    sharesStoragePool = true,
                ),
            ).hasSufficientStorage,
        )
    }

    @Test
    fun `unknown storage topology is conservative and separate pools require both sides`() {
        val baseline = planWithTopology(unlimitedTopology()).storageEstimate

        assertFalse(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = baseline.cacheRequiredBytes - 1L,
                    publicAvailableBytes = Long.MAX_VALUE,
                    sharesStoragePool = false,
                ),
            ).hasSufficientStorage,
        )
        assertFalse(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = Long.MAX_VALUE,
                    publicAvailableBytes = baseline.publicRequiredBytes - 1L,
                    sharesStoragePool = false,
                ),
            ).hasSufficientStorage,
        )
        assertFalse(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = baseline.cacheRequiredBytes,
                    publicAvailableBytes = baseline.publicRequiredBytes,
                    sharesStoragePool = null,
                ),
            ).hasSufficientStorage,
        )
        assertTrue(
            planWithTopology(
                AudioStorageTopology(
                    cacheAvailableBytes = baseline.sharedPoolRequiredBytes,
                    publicAvailableBytes = baseline.sharedPoolRequiredBytes,
                    sharesStoragePool = null,
                ),
            ).hasSufficientStorage,
        )
    }

    @Test
    fun `all estimate arithmetic saturates instead of wrapping`() {
        val plan =
            AudioExtractPlan.create(
                request = request(mode = AudioExtractMode.COPY),
                durationUs = Long.MAX_VALUE,
                sourceAudioBitrate = Int.MAX_VALUE,
                storageTopology = unlimitedTopology(),
            )

        assertEquals(Long.MAX_VALUE, plan.storageEstimate.audioBytes)
        assertEquals(Long.MAX_VALUE, plan.storageEstimate.upperAudioBytes)
        assertEquals(Long.MAX_VALUE, plan.storageEstimate.estimatedMinBytes)
        assertEquals(Long.MAX_VALUE, plan.storageEstimate.estimatedMaxBytes)
        assertEquals(Long.MAX_VALUE, plan.storageEstimate.cacheRequiredBytes)
        assertEquals(Long.MAX_VALUE, plan.storageEstimate.publicRequiredBytes)
        assertEquals(Long.MAX_VALUE, plan.storageEstimate.sharedPoolRequiredBytes)
        assertTrue(plan.hasSufficientStorage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative duration is rejected rather than producing a wrapped estimate`() {
        AudioExtractPlan.create(
            request = request(mode = AudioExtractMode.COPY),
            durationUs = -1L,
            sourceAudioBitrate = 128_000,
            storageTopology = unlimitedTopology(),
        )
    }

    @Test
    fun `storage estimates are gates only and do not validate completed output bitrate`() {
        val plan =
            AudioExtractPlan.create(
                request = request(mode = AudioExtractMode.AAC, bitrate = 96_000),
                durationUs = 1_000_000L,
                sourceAudioBitrate = 1,
                storageTopology = unlimitedTopology(),
            )

        // The plan exposes only a preflight result. Post-processing structural validation belongs
        // to the output verifier and must not compare an actual bitrate with this estimate.
        assertEquals(96_000, plan.estimateBitrateBps)
        assertTrue(plan.hasSufficientStorage)
    }

    private fun planWithTopology(topology: AudioStorageTopology): AudioExtractPlan =
        AudioExtractPlan.create(
            request = request(mode = AudioExtractMode.COPY),
            durationUs = 10_000_000L,
            sourceAudioBitrate = null,
            storageTopology = topology,
        )

    private fun unlimitedTopology(): AudioStorageTopology =
        AudioStorageTopology(
            cacheAvailableBytes = Long.MAX_VALUE,
            publicAvailableBytes = Long.MAX_VALUE,
            sharesStoragePool = false,
        )

    private fun request(
        mode: AudioExtractMode,
        bitrate: Int? = null,
    ): AudioExtractRequest =
        AudioExtractRequest(
            sourceUri = "content://media/external/video/media/42",
            outputFileName = "lecture.m4a",
            outputTreeUri = null,
            outputLocationLabel = "系统音频 > Music > VideoSlim",
            mode = mode,
            bitrate = bitrate,
        )
}
