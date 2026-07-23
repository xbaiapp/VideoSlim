package com.videoslim.videoslim

import androidx.media3.common.Metadata
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.muxer.MuxerUtil
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMetadataPolicyTest {
    @Test
    fun `initial parsed values replace source entries and remove unrelated metadata`() {
        val captureTime = Instant.parse("2026-07-22T10:30:15Z").toEpochMilli()
        val location = CaptureLocation(37.421998, -122.084)
        val policy = CaptureMetadataPolicy(SourceCaptureMetadata(captureTime, location))
        val entries =
            linkedSetOf<Metadata.Entry>(
                Mp4TimestampData(2_500_000_000L, 2_500_000_000L),
                Mp4LocationData(1.0f, 2.0f),
                Mp4OrientationData(90),
            )

        policy.updateMetadataEntries(entries)

        assertEquals(2, entries.size)
        val timestamp = entries.filterIsInstance<Mp4TimestampData>().single()
        assertEquals(
            Mp4TimestampData.unixTimeToMp4TimeSeconds(captureTime),
            timestamp.creationTimestampSeconds,
        )
        assertEquals(timestamp.creationTimestampSeconds, timestamp.modificationTimestampSeconds)
        val writtenLocation = entries.filterIsInstance<Mp4LocationData>().single()
        assertEquals(location.latitude, writtenLocation.latitude.toDouble(), 0.000001)
        assertEquals(location.longitude, writtenLocation.longitude.toDouble(), 0.000001)
        assertFalse(entries.any { it is Mp4OrientationData })
        assertEquals(SourceCaptureMetadata(captureTime, location), policy.resolvedMetadata())
    }

    @Test
    fun `valid Media3 entries fill fields missing from the reader`() {
        val captureTime = Instant.parse("2024-04-05T06:07:08Z").toEpochMilli()
        val mp4Time = Mp4TimestampData.unixTimeToMp4TimeSeconds(captureTime)
        val policy = CaptureMetadataPolicy(SourceCaptureMetadata.EMPTY)
        val entries =
            linkedSetOf<Metadata.Entry>(
                Mp4TimestampData(mp4Time, mp4Time),
                Mp4LocationData(-33.8688f, 151.2093f),
            )

        policy.updateMetadataEntries(entries)

        val resolved = policy.resolvedMetadata()
        assertEquals(captureTime, resolved.captureTimeEpochMs)
        val resolvedLocation = checkNotNull(resolved.location)
        assertEquals(-33.8688, resolvedLocation.latitude, 0.00001)
        assertEquals(151.2093, resolvedLocation.longitude, 0.00001)
        assertEquals(2, entries.size)
    }

    @Test
    fun `missing capture time writes only the 1904 sentinel and omits invalid location`() {
        val policy =
            CaptureMetadataPolicy(
                SourceCaptureMetadata(
                    captureTimeEpochMs = null,
                    location = CaptureLocation(Double.NaN, 20.0),
                ),
            )
        val entries = linkedSetOf<Metadata.Entry>(Mp4TimestampData(0L, 0L))

        policy.updateMetadataEntries(entries)

        assertEquals(1, entries.size)
        val sentinel = entries.filterIsInstance<Mp4TimestampData>().single()
        assertEquals(0L, sentinel.creationTimestampSeconds)
        assertEquals(0L, sentinel.modificationTimestampSeconds)
        assertTrue(MuxerUtil.isMetadataSupported(sentinel))
        assertNull(policy.resolvedMetadata().captureTimeEpochMs)
        assertNull(policy.resolvedMetadata().location)
    }

    @Test
    fun `location-only source also writes the 1904 sentinel instead of processing time`() {
        val location = CaptureLocation(51.5074, -0.1278)
        val policy =
            CaptureMetadataPolicy(
                SourceCaptureMetadata(
                    captureTimeEpochMs = null,
                    location = location,
                ),
            )
        val entries = linkedSetOf<Metadata.Entry>()

        policy.updateMetadataEntries(entries)

        assertEquals(2, entries.size)
        val sentinel = entries.filterIsInstance<Mp4TimestampData>().single()
        assertEquals(0L, sentinel.creationTimestampSeconds)
        assertEquals(0L, sentinel.modificationTimestampSeconds)
        assertTrue(MuxerUtil.isMetadataSupported(sentinel))
        val writtenLocation = entries.filterIsInstance<Mp4LocationData>().single()
        assertEquals(location.latitude, writtenLocation.latitude.toDouble(), 0.000001)
        assertEquals(location.longitude, writtenLocation.longitude.toDouble(), 0.000001)
        assertEquals(SourceCaptureMetadata(null, location), policy.resolvedMetadata())
    }

    @Test
    fun `safe string representations reveal presence but not sensitive values`() {
        val captureTime = Instant.parse("2026-07-22T10:30:15Z").toEpochMilli()
        val metadata =
            SourceCaptureMetadata(
                captureTimeEpochMs = captureTime,
                location = CaptureLocation(37.421998, -122.084),
            )

        val rendered = metadata.toString()

        assertTrue(rendered.contains("captureTimePresent=true"))
        assertTrue(rendered.contains("locationPresent=true"))
        assertFalse(rendered.contains(captureTime.toString()))
        assertFalse(rendered.contains("37.421998"))
        assertFalse(rendered.contains("-122.084"))
    }
}
