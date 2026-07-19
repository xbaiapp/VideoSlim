package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCodecPolicyTest {
    @Test
    fun `video policy excludes known software codecs even when flags are wrong`() {
        val candidates =
            listOf(
                candidate("c2.google.hevc.encoder", hardware = true, software = false, vendor = false),
                candidate("OMX.google.h264.encoder", hardware = true, software = false, vendor = false),
                candidate("c2.android.hevc.encoder", hardware = true, software = false, vendor = false),
                candidate("c2.exynos.hevc.encoder", hardware = true, software = false, vendor = true),
            )

        val selected = HardwareCodecPolicy.select(candidates, "video/hevc", encoder = true)

        assertEquals(listOf("c2.exynos.hevc.encoder"), selected.map { it.name })
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
