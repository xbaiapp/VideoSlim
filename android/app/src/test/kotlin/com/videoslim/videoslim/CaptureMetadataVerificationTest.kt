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
    fun `empty expectations do not read the output file`() {
        var reads = 0
        val verifier =
            CaptureMetadataFileVerifier {
                reads += 1
                SourceCaptureMetadata.EMPTY
            }

        verifier.verify(File("missing.mp4"), SourceCaptureMetadata.EMPTY)

        assertEquals(0, reads)
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

        assertEquals(setOf("location"), error.mismatchFields)
        assertTrue(!error.message!!.contains("-33.8688"))
        assertTrue(!error.message!!.contains("151.2093"))
    }
}
