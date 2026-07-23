package com.videoslim.videoslim

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureMetadataParserTest {
    @Test
    fun `parses compact UTC dates with and without fractional seconds`() {
        assertEquals(
            Instant.parse("2026-07-22T10:30:15.123Z").toEpochMilli(),
            CaptureMetadataParser.parseCaptureTime("20260722T103015.123Z"),
        )
        assertEquals(
            Instant.parse("2026-07-22T10:30:15Z").toEpochMilli(),
            CaptureMetadataParser.parseCaptureTime("20260722T103015Z"),
        )
    }

    @Test
    fun `parses ISO dates with an explicit offset as the same instant`() {
        assertEquals(
            Instant.parse("2026-07-22T02:30:15.123Z").toEpochMilli(),
            CaptureMetadataParser.parseCaptureTime("2026-07-22T10:30:15.123+08:00"),
        )
        assertEquals(
            Instant.parse("2026-07-22T02:30:15Z").toEpochMilli(),
            CaptureMetadataParser.parseCaptureTime("20260722T103015+0800"),
        )
    }

    @Test
    fun `rejects dates without an explicit timezone and malformed dates`() {
        assertNull(CaptureMetadataParser.parseCaptureTime("2026-07-22T10:30:15"))
        assertNull(CaptureMetadataParser.parseCaptureTime("20260722T103015"))
        assertNull(CaptureMetadataParser.parseCaptureTime("2026-02-30T10:30:15Z"))
        assertNull(CaptureMetadataParser.parseCaptureTime("not-a-date"))
        assertNull(CaptureMetadataParser.parseCaptureTime(""))
        assertNull(CaptureMetadataParser.parseCaptureTime(null))
    }

    @Test
    fun `container date wins and MediaStore date taken is a validated fallback`() {
        val containerTime = Instant.parse("2026-07-22T10:30:15Z").toEpochMilli()
        val mediaStoreTime = Instant.parse("2026-07-21T09:00:00Z").toEpochMilli()

        assertEquals(
            containerTime,
            CaptureMetadataParser.chooseCaptureTime(
                retrieverDate = "20260722T103015Z",
                mediaStoreDateTakenEpochMs = mediaStoreTime,
            ),
        )
        assertEquals(
            mediaStoreTime,
            CaptureMetadataParser.chooseCaptureTime(
                retrieverDate = "not-a-date",
                mediaStoreDateTakenEpochMs = mediaStoreTime,
            ),
        )
        assertNull(
            CaptureMetadataParser.chooseCaptureTime(
                retrieverDate = null,
                mediaStoreDateTakenEpochMs = 0L,
            ),
        )
    }

    @Test
    fun `1904 MP4 sentinel is never interpreted as source capture time`() {
        assertNull(CaptureMetadataParser.unixTimeFromMp4Seconds(0L))
        assertNull(CaptureMetadataParser.parseCaptureTime("1904-01-01T00:00:00Z"))
        assertNull(CaptureMetadataParser.parseCaptureTime("19040101T000000Z"))
    }

    @Test
    fun `parses ISO 6709 latitude longitude and optional altitude`() {
        assertEquals(
            CaptureLocation(latitude = 37.421998, longitude = -122.084),
            CaptureMetadataParser.parseLocation("+37.421998-122.084000/"),
        )
        assertEquals(
            CaptureLocation(latitude = -33.8688, longitude = 151.2093),
            CaptureMetadataParser.parseLocation("-33.8688+151.2093+0012.5/"),
        )
        assertEquals(
            CaptureLocation(latitude = 0.0, longitude = 0.0),
            CaptureMetadataParser.parseLocation("+00.0000+000.0000/"),
        )
    }

    @Test
    fun `accepts location boundaries and rejects malformed or out of range values`() {
        assertEquals(
            CaptureLocation(latitude = 90.0, longitude = -180.0),
            CaptureMetadataParser.parseLocation("+90.0000-180.0000/"),
        )
        assertNull(CaptureMetadataParser.parseLocation("+90.0001-180.0000/"))
        assertNull(CaptureMetadataParser.parseLocation("+45.0000-180.0001/"))
        assertNull(CaptureMetadataParser.parseLocation("37.421998-122.084000/"))
        assertNull(CaptureMetadataParser.parseLocation("+37.421998/"))
        assertNull(CaptureMetadataParser.parseLocation("+NaN-122.084/"))
        assertNull(CaptureMetadataParser.parseLocation("+Infinity-122.084/"))
        assertNull(CaptureMetadataParser.parseLocation(""))
        assertNull(CaptureMetadataParser.parseLocation(null))
    }
}
