package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class SerializableArgumentsTest {
    @Test
    fun `copies the bounded method-channel request without aliases`() {
        val video = linkedMapOf<String, Any?>("codec" to "hevc", "bitrate" to 2_500_000, "longEdge" to null)
        val source =
            linkedMapOf<String, Any?>(
                "uri" to "content://media/source/1",
                "video" to video,
                "audio" to mapOf("mode" to "copy", "bitrate" to null),
            )

        val copied = SerializableArguments.copy(source)
        val copiedVideo = copied["video"] as Map<*, *>
        video["codec"] = "h264"

        assertNotSame(source, copied)
        assertEquals("hevc", copiedVideo["codec"])
        assertEquals(2_500_000, copiedVideo["bitrate"])
    }

    @Test
    fun `rejects non-string keys unsupported values and excessive depth`() {
        assertRejected { SerializableArguments.copy(mapOf(1 to "bad")) }
        assertRejected { SerializableArguments.copy(mapOf("bad" to listOf(1))) }
        assertRejected {
            SerializableArguments.copy(
                mapOf(
                    "a" to
                        mapOf(
                            "b" to
                                mapOf(
                                    "c" to
                                        mapOf("d" to mapOf("e" to mapOf("f" to 1))),
                                ),
                        ),
                ),
            )
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
