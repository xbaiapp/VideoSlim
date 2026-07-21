package com.videoslim.videoslim

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogEntryNormalizerTest {
    @Test
    fun `huge control-heavy input inspects and retains only a bounded prefix`() {
        val byteLimit = 257
        val input = "\u0001\n\t\r".repeat(1_000_000)

        val normalized = AppLogEntryNormalizer.normalizePrefixBounded(input, byteLimit)

        assertTrue(normalized.truncated)
        assertTrue(normalized.inspectedCodeUnits <= byteLimit + 2)
        assertEquals(normalized.utf8ByteCount, AppLogEntryNormalizer.utf8Size(normalized.value))
        assertTrue(normalized.utf8ByteCount <= byteLimit)
        assertTrue(normalized.value.endsWith(AppLogEntryNormalizer.TRUNCATION_MARKER))
        assertFalse(normalized.value.any { it.code < 0x20 })
        assertTrue(normalized.value.startsWith("\\u0001\\n\\t\\r"))
    }

    @Test
    fun `huge multibyte input remains valid Unicode within the byte cap`() {
        val byteLimit = 259
        val input = "猫😀".repeat(1_000_000)

        val normalized = AppLogEntryNormalizer.normalizePrefixBounded(input, byteLimit)

        assertTrue(normalized.truncated)
        assertTrue(normalized.inspectedCodeUnits <= byteLimit + 2)
        assertEquals(normalized.utf8ByteCount, AppLogEntryNormalizer.utf8Size(normalized.value))
        assertTrue(normalized.utf8ByteCount <= byteLimit)
        assertTrue(normalized.value.endsWith(AppLogEntryNormalizer.TRUNCATION_MARKER))
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(normalized.value))
        assertFalse(normalized.value.contains('\uFFFD'))
    }

    @Test
    fun `bounded normalization uses the full cap before deciding truncation`() {
        val exact = AppLogEntryNormalizer.normalizePrefixBounded("abcdef", byteLimit = 8)
        val truncated = AppLogEntryNormalizer.normalizePrefixBounded("abcdefghijk", byteLimit = 8)

        assertFalse(exact.truncated)
        assertEquals("abcdef", exact.value)
        assertEquals(6, exact.utf8ByteCount)
        assertEquals("abcde...", truncated.value)
        assertEquals(8, truncated.utf8ByteCount)
    }
}