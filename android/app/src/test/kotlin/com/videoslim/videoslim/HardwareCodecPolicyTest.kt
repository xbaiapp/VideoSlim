package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCodecPolicyTest {
    @Test
    fun `explicit hardware classification wins over a legacy software name heuristic`() {
        val candidates =
            listOf(
                candidate("c2.google.hevc.encoder", hardware = true, software = false, vendor = false),
                candidate("c2.android.hevc.encoder", hardware = false, software = true, vendor = false),
                candidate("OMX.google.h264.encoder", hardware = false, software = true, vendor = false),
                candidate("vendor.conflicting.encoder", hardware = true, software = true, vendor = true),
                candidate("c2.exynos.hevc.encoder", hardware = true, software = false, vendor = true),
            )

        val selected = HardwareCodecPolicy.select(candidates, "video/hevc", encoder = true)

        assertEquals(
            listOf("c2.exynos.hevc.encoder", "c2.google.hevc.encoder"),
            selected.map { it.name },
        )
    }

    @Test
    fun `vendor non-software codec remains eligible when hardware flag is inconclusive`() {
        val candidates =
            listOf(
                candidate("c2.pixel.hevc.encoder", hardware = false, software = false, vendor = true),
                candidate("platform.ambiguous.encoder", hardware = false, software = false, vendor = false),
                candidate("c2.google.hevc.encoder", hardware = false, software = false, vendor = true),
            )

        val selected = HardwareCodecPolicy.select(candidates, "video/hevc", encoder = true)

        assertEquals(listOf("c2.pixel.hevc.encoder"), selected.map { it.name })
    }

    @Test
    fun `vendor hardware codecs are ranked before other hardware codecs`() {
        val candidates =
            listOf(
                candidate("vendor.neutral.encoder", vendor = false),
                candidate("vendor.pixel.encoder", vendor = true),
            )

        val selected = HardwareCodecPolicy.select(candidates, "video/hevc", encoder = true)

        assertEquals(listOf("vendor.pixel.encoder", "vendor.neutral.encoder"), selected.map { it.name })
    }

    @Test
    fun `role mime and hardware properties are mandatory`() {
        val candidates =
            listOf(
                candidate("wrong.role", encoder = false),
                candidate("wrong.mime", types = setOf("video/avc")),
                candidate("software.flag", hardware = false, software = true),
                candidate("eligible", encoder = true),
            )

        val selected = HardwareCodecPolicy.select(candidates, "video/hevc", encoder = true)

        assertEquals(listOf("eligible"), selected.map { it.name })
        assertTrue(HardwareCodecPolicy.isKnownSoftwareCodec("c2.google.avc.decoder"))
        assertFalse(HardwareCodecPolicy.isKnownSoftwareCodec("c2.exynos.avc.decoder"))
    }

    @Test
    fun `software decoder compatibility mode requires the platform software-only flag`() {
        val candidates =
            listOf(
                candidate(
                    "c2.google.avc.decoder",
                    encoder = false,
                    hardware = true,
                    software = false,
                    vendor = false,
                    types = setOf("video/avc"),
                ),
                candidate(
                    "c2.android.avc.decoder",
                    encoder = false,
                    hardware = false,
                    software = true,
                    vendor = false,
                    types = setOf("video/avc"),
                ),
                candidate(
                    "software.encoder",
                    encoder = true,
                    hardware = false,
                    software = true,
                    vendor = false,
                    types = setOf("video/avc"),
                ),
                candidate(
                    "wrong.mime.decoder",
                    encoder = false,
                    hardware = false,
                    software = true,
                    vendor = false,
                    types = setOf("video/hevc"),
                ),
            )

        val selected = HardwareCodecPolicy.selectSoftwareDecoders(candidates, "video/avc")

        assertEquals(listOf("c2.android.avc.decoder"), selected.map { it.name })
        assertTrue(
            HardwareCodecPolicy.selectSoftwareDecoders(
                candidates,
                "video/avc",
                platformSoftwareFlagAvailable = false,
            ).isEmpty(),
        )
    }

    private fun candidate(
        name: String,
        encoder: Boolean = true,
        hardware: Boolean = true,
        software: Boolean = false,
        vendor: Boolean = true,
        types: Set<String> = setOf("video/hevc"),
    ) =
        CodecCandidate(
            name = name,
            isEncoder = encoder,
            isHardwareAccelerated = hardware,
            isSoftwareOnly = software,
            isVendor = vendor,
            supportedTypes = types,
        )
}
