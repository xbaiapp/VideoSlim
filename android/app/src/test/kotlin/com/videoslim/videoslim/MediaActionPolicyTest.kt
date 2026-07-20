package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun acceptsOnlyExactVideoSlimOutputMimeTypes() {
        assertEquals(
            MediaActionMediaKind.VIDEO,
            MediaActionMediaKind.fromResolvedMimeType("video/mp4"),
        )
        assertEquals(
            MediaActionMediaKind.AUDIO,
            MediaActionMediaKind.fromResolvedMimeType("audio/mp4"),
        )
        assertEquals("分享压缩视频", MediaActionMediaKind.VIDEO.chooserTitle)
        assertEquals("分享提取的音频", MediaActionMediaKind.AUDIO.chooserTitle)
        listOf(null, "audio/m4a", "audio/aac", "video/webm", "application/octet-stream").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                MediaActionMediaKind.fromResolvedMimeType(it)
            }
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
