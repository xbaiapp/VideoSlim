package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MediaActionPolicyTest {
    @Test
    fun acceptsOnlyTrimmedContentUrisWithAuthorities() {
        val uri = MediaActionPolicy.validatedContentUri("content://media/external/video/media/42")

        assertEquals("content://media/external/video/media/42", uri)
    }

    @Test
    fun rejectsBlankNetworkFileAndOpaqueUris() {
        listOf(
            null,
            "",
            " ",
            "https://example.test/video.mp4",
            "file:///sdcard/video.mp4",
            "content:opaque",
            " content://media/video/42",
            "content://media/video/42\n",
        ).forEach { value ->
            assertRejected(value ?: "null") {
                MediaActionPolicy.validatedContentUri(value)
            }
        }
    }

    @Test
    fun rejectsUnboundedUris() {
        val oversized = "content://media/" + "a".repeat(4_096)

        assertRejected("oversized URI") {
            MediaActionPolicy.validatedContentUri(oversized)
        }
    }

    private fun assertRejected(
        label: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected IllegalArgumentException for $label")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
