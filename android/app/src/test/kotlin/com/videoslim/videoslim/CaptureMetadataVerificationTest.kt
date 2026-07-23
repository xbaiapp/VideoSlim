package com.videoslim.videoslim

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CaptureMetadataVerificationTest {
    @Test
    fun `verification accepts MP4 second precision and location float tolerance`() {
        val expected =
            SourceCaptureMetadata(
                captureTimeEpochMs = Instant.parse("2026-07-22T10:30:15.987Z").toEpochMilli(),
                location = CaptureLocation(37.421998, -122.084),
            )
        val actual =
            SourceCaptureMetadata(
                captureTimeEpochMs = Instant.parse("2026-07-22T10:30:15Z").toEpochMilli(),
                location = CaptureLocation(37.42204, -122.08395),
            )
        val verifier = CaptureMetadataFileVerifier { actual }

        verifier.verify(File("unused.mp4"), expected)
    }

    @Test
    fun `verification reports only field names for missing or mismatched values`() {
        val expected =
            SourceCaptureMetadata(
                captureTimeEpochMs = Instant.parse("2026-07-22T10:30:15Z").toEpochMilli(),
                location = CaptureLocation(37.421998, -122.084),
            )
        val actual =
            SourceCaptureMetadata(
                captureTimeEpochMs = Instant.parse("2026-07-22T10:30:17Z").toEpochMilli(),
                location = null,
            )
        val verifier = CaptureMetadataFileVerifier { actual }

        val error =
            try {
                verifier.verify(File("unused.mp4"), expected)
                fail("Expected verification to fail")
                error("unreachable")
            } catch (exception: CaptureMetadataVerificationException) {
                exception
            }

        assertEquals(setOf("captureTime", "location"), error.mismatchFields)
        assertTrue(error.message!!.contains("captureTime,location"))
        assertTrue(!error.message!!.contains("37.421998"))
        assertTrue(!error.message!!.contains("-122.084"))
    }

    @Test
    fun `empty expectations still read and accept a metadata-empty output`() {
        var reads = 0
        val verifier =
            CaptureMetadataFileVerifier {
                reads += 1
                SourceCaptureMetadata.EMPTY
            }

        verifier.verify(File("missing.mp4"), SourceCaptureMetadata.EMPTY)

        assertEquals(1, reads)
    }

    @Test
    fun `empty expectations reject processing time and unexpected location`() {
        val actual =
            SourceCaptureMetadata(
                captureTimeEpochMs = Instant.parse("2026-07-23T01:02:03Z").toEpochMilli(),
                location = CaptureLocation(51.5074, -0.1278),
            )
        val verifier = CaptureMetadataFileVerifier { actual }

        val error =
            try {
                verifier.verify(File("unused.mp4"), SourceCaptureMetadata.EMPTY)
                fail("Expected verification to fail")
                error("unreachable")
            } catch (exception: CaptureMetadataVerificationException) {
                exception
            }

        assertEquals(setOf("captureTime", "location"), error.mismatchFields)
    }

    @Test
    fun `location-only expectation rejects an unexpected timestamp`() {
        val location = CaptureLocation(-33.8688, 151.2093)
        val expected = SourceCaptureMetadata(captureTimeEpochMs = null, location = location)
        val actual =
            SourceCaptureMetadata(
                captureTimeEpochMs = Instant.parse("2026-07-23T01:02:03Z").toEpochMilli(),
                location = location,
            )
        val verifier = CaptureMetadataFileVerifier { actual }

        val error =
            try {
                verifier.verify(File("unused.mp4"), expected)
                fail("Expected verification to fail")
                error("unreachable")
            } catch (exception: CaptureMetadataVerificationException) {
                exception
            }

        assertEquals(setOf("captureTime"), error.mismatchFields)
    }

    @Test
    fun `read failure reports expected field names without source values`() {
        val expected =
            SourceCaptureMetadata(
                captureTimeEpochMs = null,
                location = CaptureLocation(-33.8688, 151.2093),
            )
        val verifier = CaptureMetadataFileVerifier { throw IllegalStateException("reader failed") }

        val error =
            try {
                verifier.verify(File("unused.mp4"), expected)
                fail("Expected verification to fail")
                error("unreachable")
            } catch (exception: CaptureMetadataVerificationException) {
                exception
            }

        assertEquals(setOf("captureTime", "location"), error.mismatchFields)
        assertTrue(!error.message!!.contains("-33.8688"))
        assertTrue(!error.message!!.contains("151.2093"))
    }
}
