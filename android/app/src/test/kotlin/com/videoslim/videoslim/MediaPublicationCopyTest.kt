package com.videoslim.videoslim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPublicationCopyTest {
    @Test
    fun `copies all bytes while publication remains active`() {
        val source = ByteArray(37) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied =
            copyPublicationBytes(ByteArrayInputStream(source), output, { false }, bufferSize = 8)

        assertArrayEquals(source, output.toByteArray())
        assertEquals(source.size.toLong(), copied)
    }

    @Test
    fun `cancellation stops between bounded copy chunks`() {
        val output = ByteArrayOutputStream()
        var checks = 0

        assertThrows(IOException::class.java) {
            copyPublicationBytes(
                ByteArrayInputStream(ByteArray(24)),
                output,
                shouldCancel = { ++checks > 1 },
                bufferSize = 8,
            )
        }

        assertEquals(8, output.size())
    }

    @Test
    fun `readback counting detects short exact and oversized provider outputs`() {
        assertEquals(
            7L,
            countPublicationBytes(
                ByteArrayInputStream(ByteArray(7)),
                shouldCancel = { false },
                stopAfterBytes = 9,
                bufferSize = 3,
            ),
        )
        requirePublishedByteCount(expectedBytes = 7, observedBytes = 7)
        assertThrows(IOException::class.java) {
            requirePublishedByteCount(expectedBytes = 8, observedBytes = 7)
        }
        assertThrows(IOException::class.java) {
            requirePublishedByteCount(expectedBytes = 6, observedBytes = 7)
        }
    }

    @Test
    fun `legacy path is deleted only after MediaStore row removal is confirmed`() {
        assertEquals(false, shouldDeleteLegacyPath(rowRemovalConfirmed = false))
        assertEquals(true, shouldDeleteLegacyPath(rowRemovalConfirmed = true))
    }
}
