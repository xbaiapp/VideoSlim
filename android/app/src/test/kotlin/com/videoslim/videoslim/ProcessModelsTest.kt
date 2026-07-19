package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProcessModelsTest {
    @Test
    fun `parses the exact M1 nested request`() {
        val request = ProcessRequest.parse(validArguments())

        assertEquals("content://media/external/video/media/42", request.sourceUri)
        assertEquals("lecture_slim.mp4", request.outputFileName)
        assertEquals(2_500_000, request.videoBitrate)
    }

    @Test
    fun `accepts a channel long when it is an in-range integer`() {
        val request = ProcessRequest.parse(
            validArguments(video = validVideo().apply { this["bitrate"] = 2_500_000L }),
        )

        assertEquals(2_500_000, request.videoBitrate)
    }

    @Test
    fun `rejects malformed root maps and exact-key violations`() {
        assertRejected(null)
        assertRejected("not a map")
        assertRejected(validArguments().apply { remove("uri") })
        assertRejected(validArguments().apply { this["extra"] = true })
    }

    @Test
    fun `rejects invalid source URI and unsafe output names`() {
        val invalidUris = listOf(
            null,
            7,
            "",
            "   ",
            "content://",
            "content:///missing-authority",
            "content://bad authority/video",
            "file:///sdcard/source.mp4",
            "https://example/video.mp4",
        )
        invalidUris.forEach { invalid ->
            assertRejected(validArguments().apply { this["uri"] = invalid })
        }

        val invalidNames = listOf(
            null,
            7,
            "",
            "   ",
            ".",
            "..",
            "folder/output.mp4",
            "folder\\output.mp4",
            "bad:name.mp4",
            "bad?.mp4",
            "no-extension",
            ".mp4",
            "bad\u0000name.mp4",
            "界".repeat(100) + ".mp4",
            "x".repeat(256),
        )
        invalidNames.forEach { invalid ->
            assertRejected(validArguments().apply { this["outputFileName"] = invalid })
        }
    }

    @Test
    fun `rejects malformed video maps`() {
        assertRejected(validArguments().apply { this["video"] = null })
        assertRejected(validArguments().apply { this["video"] = "not a map" })
        validVideo().keys.forEach { key ->
            assertRejected(validArguments(video = validVideo().apply { remove(key) }))
        }
        assertRejected(validArguments(video = validVideo().apply { this["extra"] = null }))
        assertRejected(validArguments(video = validVideo().apply { this[1] = null }))
    }

    @Test
    fun `rejects codecs and bitrates outside the M1 policy`() {
        listOf(null, 1, "h264", "HEVC", "av1").forEach { value ->
            assertRejected(validArguments(video = validVideo().apply { this["codec"] = value }))
        }
        listOf(null, "2500000", 0, -1, 2_500_000.0, Long.MAX_VALUE).forEach { value ->
            assertRejected(validArguments(video = validVideo().apply { this["bitrate"] = value }))
        }
    }

    @Test
    fun `explicitly rejects every out-of-M1 video option`() {
        mapOf<String, Any?>(
            "longEdge" to 1920,
            "crop" to mapOf("left" to 0, "top" to 0, "width" to 100, "height" to 100),
            "trimStartMs" to 0,
            "trimEndMs" to 10_000,
        ).forEach { (key, value) ->
            assertRejected(validArguments(video = validVideo().apply { this[key] = value }))
        }
    }

    @Test
    fun `rejects malformed audio maps and every out-of-M1 audio option`() {
        assertRejected(validArguments().apply { this["audio"] = null })
        assertRejected(validArguments().apply { this["audio"] = "not a map" })
        validAudio().keys.forEach { key ->
            assertRejected(validArguments(audio = validAudio().apply { remove(key) }))
        }
        assertRejected(validArguments(audio = validAudio().apply { this["extra"] = null }))
        listOf(null, 1, "reencode", "remove", "COPY").forEach { value ->
            assertRejected(validArguments(audio = validAudio().apply { this["mode"] = value }))
        }
        listOf(64_000, 64_000L, 0, "64000").forEach { value ->
            assertRejected(validArguments(audio = validAudio().apply { this["bitrate"] = value }))
        }
    }

    @Test
    fun `all validation failures use stable UNKNOWN with a readable message`() {
        val exception = rejected(validArguments().apply { this["uri"] = "file:///tmp/input.mp4" })

        assertEquals(EngineErrorCode.UNKNOWN, exception.error.code)
        assertEquals("UNKNOWN", exception.error.code.wireName)
        assertTrue(exception.message.orEmpty().isNotBlank())
        assertTrue(exception.error.message.isNotBlank())
    }

    private fun assertRejected(arguments: Any?) {
        rejected(arguments)
    }

    private fun rejected(arguments: Any?): ProcessRequestException {
        try {
            ProcessRequest.parse(arguments)
            fail("Expected ProcessRequestException for $arguments")
        } catch (exception: ProcessRequestException) {
            return exception
        }
        error("unreachable")
    }

    private fun validArguments(
        video: MutableMap<Any?, Any?> = validVideo(),
        audio: MutableMap<Any?, Any?> = validAudio(),
    ): MutableMap<Any?, Any?> =
        linkedMapOf(
            "uri" to "content://media/external/video/media/42",
            "outputFileName" to "lecture_slim.mp4",
            "video" to video,
            "audio" to audio,
        )

    private fun validVideo(): MutableMap<Any?, Any?> =
        linkedMapOf(
            "codec" to "hevc",
            "bitrate" to 2_500_000,
            "longEdge" to null,
            "crop" to null,
            "trimStartMs" to null,
            "trimEndMs" to null,
        )

    private fun validAudio(): MutableMap<Any?, Any?> =
        linkedMapOf(
            "mode" to "copy",
            "bitrate" to null,
        )
}
