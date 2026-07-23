package com.videoslim.videoslim

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderCapabilityReaderTest {
    @Test
    fun `reader filters target encoders and emits deterministic complete fields`() {
        val reader =
            EncoderCapabilityReader(
                apiLevel = 36,
                codecProvider = {
                    listOf(
                        descriptor(
                            name = "decoder",
                            isEncoder = false,
                            supportedTypes = setOf("video/hevc"),
                        ),
                        descriptor(
                            name = "ignored.encoder",
                            supportedTypes = setOf("audio/mp4a-latm"),
                        ),
                        descriptor(
                            name = "z.encoder",
                            supportedTypes = setOf("video/hevc", "video/avc"),
                        ),
                        descriptor(
                            name = "a.encoder",
                            supportedTypes = setOf("VIDEO/AV01"),
                            hardware = false,
                            software = true,
                            vendor = false,
                            inspect = { declaredMimeType ->
                                assertEquals("VIDEO/AV01", declaredMimeType)
                                capability()
                            },
                        ),
                    )
                },
            )

        val report = reader.read()

        assertEquals(36, report["sdkInt"])
        assertEquals(EncoderCapabilityReader.TARGET_MIME_TYPES, report["queriedMimeTypes"])
        val entries = report.encoderEntries()
        assertEquals(
            listOf(
                "a.encoder:video/av01",
                "z.encoder:video/avc",
                "z.encoder:video/hevc",
            ),
            entries.map { "${it["name"]}:${it["mimeType"]}" },
        )
        val first = entries.first()
        assertEquals("a.encoder", first["canonicalName"])
        assertEquals(false, first["isAlias"])
        assertEquals(false, first["isHardwareAccelerated"])
        assertEquals(true, first["isSoftwareOnly"])
        assertEquals(false, first["isVendor"])
        assertEquals("platform", first["classificationSource"])
        assertEquals(true, first["supportsCq"])
        assertEquals(true, first["supportsVbr"])
        assertEquals(false, first["supportsCbr"])
        assertEquals(true, first["supportsQpBounds"])
        assertEquals(mapOf("lower" to 64_000, "upper" to 120_000_000), first["bitrateRange"])
        assertEquals(mapOf("lower" to 0, "upper" to 10), first["complexityRange"])
        assertNull(first["errorCode"])
    }

    @Test
    fun `single codec mime failure is isolated and later entries survive`() {
        val reader =
            EncoderCapabilityReader(
                apiLevel = 36,
                codecProvider = {
                    listOf(
                        descriptor(
                            name = "a.encoder",
                            supportedTypes = setOf("video/avc", "video/hevc"),
                            inspect = { mime ->
                                if (mime == "video/avc") error("vendor failure")
                                capability()
                            },
                        ),
                        descriptor(name = "b.encoder", supportedTypes = setOf("video/av01")),
                    )
                },
            )

        val entries = reader.read().encoderEntries()

        assertEquals(3, entries.size)
        val failed = entries.first()
        assertEquals("a.encoder", failed["name"])
        assertEquals("video/avc", failed["mimeType"])
        assertEquals("CAPABILITY_QUERY_FAILED", failed["errorCode"])
        assertNull(failed["supportsCq"])
        assertNull(failed["supportsVbr"])
        assertNull(failed["supportsCbr"])
        assertNull(failed["supportsQpBounds"])
        assertNull(failed["bitrateRange"])
        assertNull(failed["complexityRange"])
        assertEquals("video/hevc", entries[1]["mimeType"])
        assertNull(entries[1]["errorCode"])
        assertEquals("b.encoder", entries[2]["name"])
    }

    @Test
    fun `pre 29 platform classification and pre 31 qp remain unknown rather than false`() {
        val reader =
            EncoderCapabilityReader(
                apiLevel = 28,
                codecProvider = {
                    listOf(
                        descriptor(
                            name = "legacy.encoder",
                            supportedTypes = setOf("video/avc"),
                            canonicalName = null,
                            alias = null,
                            hardware = null,
                            software = null,
                            vendor = null,
                            inspect = { capability(qp = null) },
                        ),
                    )
                },
            )

        val entry = reader.read().encoderEntries().single()

        assertEquals("unavailable_pre29", entry["classificationSource"])
        assertNull(entry["canonicalName"])
        assertNull(entry["isAlias"])
        assertNull(entry["isHardwareAccelerated"])
        assertNull(entry["isSoftwareOnly"])
        assertNull(entry["isVendor"])
        assertNull(entry["supportsQpBounds"])
    }

    @Test
    fun `production reader is query only and cannot configure or start codecs`() {
        val source = locateProductionSource("EncoderCapabilityReader.kt").readText()

        assertTrue(source.contains("MediaCodecList"))
        assertTrue(source.contains("getCapabilitiesForType"))
        assertFalse(source.contains("MediaCodec.create"))
        assertFalse(source.contains("createByCodecName"))
        assertFalse(source.contains(".configure("))
        assertFalse(source.contains(".start("))
        assertFalse(source.contains("ProcessingService"))
        assertFalse(source.contains("androidx.media3.transformer"))
        assertFalse(source.contains("Transformer.Builder"))
    }

    private fun descriptor(
        name: String,
        isEncoder: Boolean = true,
        supportedTypes: Set<String>,
        canonicalName: String? = name,
        alias: Boolean? = false,
        hardware: Boolean? = true,
        software: Boolean? = false,
        vendor: Boolean? = true,
        inspect: (String) -> EncoderTypeCapability = { capability() },
    ): EncoderCodecDescriptor =
        EncoderCodecDescriptor(
            name = name,
            canonicalName = canonicalName,
            isEncoder = isEncoder,
            isAlias = alias,
            isHardwareAccelerated = hardware,
            isSoftwareOnly = software,
            isVendor = vendor,
            supportedTypes = supportedTypes,
            inspect = inspect,
        )

    private fun capability(qp: Boolean? = true): EncoderTypeCapability =
        EncoderTypeCapability(
            supportsCq = true,
            supportsVbr = true,
            supportsCbr = false,
            supportsQpBounds = qp,
            bitrateLower = 64_000,
            bitrateUpper = 120_000_000,
            complexityLower = 0,
            complexityUpper = 10,
        )

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.encoderEntries(): List<Map<String, Any?>> =
        this["encoders"] as List<Map<String, Any?>>

    private fun locateProductionSource(name: String): File {
        var directory = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(10) {
            val candidates =
                listOf(
                    directory.resolve("src/main/kotlin/com/videoslim/videoslim/$name"),
                    directory.resolve("app/src/main/kotlin/com/videoslim/videoslim/$name"),
                    directory.resolve("android/app/src/main/kotlin/com/videoslim/videoslim/$name"),
                )
            candidates.firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate $name")
    }
}
