package com.videoslim.videoslim

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `count rotation retains newest complete lines`() {
        val store = newStore(maxLines = 3, maxBytes = 1024)

        repeat(5) { index -> store.append("line-$index") }

        assertEquals("line-2\nline-3\nline-4\n", store.readAll())
    }

    @Test
    fun `utf8 rotation and truncation honor bytes including newline`() {
        val store = newStore(maxLines = 10, maxBytes = 13, maxEntryBytes = 8)

        store.append("abcdefghijk")
        assertEquals("abcde...\n", store.readAll())
        assertTrue(store.readAll().toByteArray(StandardCharsets.UTF_8).size <= 13)

        store.append("猫猫猫")
        val text = store.readAll()
        assertTrue(text.toByteArray(StandardCharsets.UTF_8).size <= 13)
        assertFalse(text.contains('\uFFFD'))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `control characters remain one physical line`() {
        val store = newStore(maxLines = 10, maxBytes = 1024)

        store.append("a\rb\nc\td\u0001e")

        assertEquals("a\\rb\\nc\\td\\u0001e\n", store.readAll())
        assertEquals(1, store.readAll().lineSequence().count { it.isNotEmpty() })
    }

    @Test
    fun `share snapshot bytes exactly equal read all`() {
        val store = newStore(maxLines = 10, maxBytes = 1024)
        store.append("one")
        store.append("two")

        val expected = store.readAll().toByteArray(StandardCharsets.UTF_8)
        val snapshot = store.createShareSnapshot()

        assertTrue(snapshot.isFile)
        assertTrue(expected.contentEquals(snapshot.readBytes()))
    }

    private fun newStore(
        maxLines: Int,
        maxBytes: Int,
        maxEntryBytes: Int = AppLogStore.DEFAULT_MAX_ENTRY_BYTES,
    ): AppLogStore {
        val root = temporaryFolder.newFolder()
        return AppLogStore(
            filesDir = File(root, "files"),
            cacheDir = File(root, "cache"),
            maxLines = maxLines,
            maxBytes = maxBytes,
            maxEntryBytes = maxEntryBytes,
        )
    }
}
