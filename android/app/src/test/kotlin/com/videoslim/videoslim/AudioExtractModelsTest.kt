package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioExtractModelsTest {
    @Test
    fun `task kinds use stable wires and only legacy video parsing defaults a missing kind`() {
        assertEquals(TaskKind.VIDEO_COMPRESSION, TaskKind.fromWireName("video_compression"))
        assertEquals(TaskKind.AUDIO_EXTRACTION, TaskKind.fromWireName("audio_extraction"))
        assertNull(TaskKind.fromWireName(null))
        assertNull(TaskKind.fromWireName("videoCompression"))

        assertEquals(TaskKind.VIDEO_COMPRESSION, TaskKind.fromLegacyVideoWireName(null))
        assertEquals(
            TaskKind.AUDIO_EXTRACTION,
            TaskKind.fromLegacyVideoWireName("audio_extraction"),
        )
    }

    @Test
    fun `parses and serializes the exact nested audio extraction request`() {
        val request = AudioExtractRequest.parse(validArguments())

        assertEquals("content://media/external/video/media/42", request.sourceUri)
        assertEquals("lecture_slim_20260720_115739.m4a", request.outputFileName)
        assertNull(request.outputTreeUri)
        assertEquals("系统音频 > Music > VideoSlim", request.outputLocationLabel)
        assertEquals(AudioExtractMode.COPY, request.mode)
        assertNull(request.bitrate)
        assertEquals(validArguments(), request.toChannelMap())
    }

    @Test
    fun `parses a content tree destination and every AAC bitrate`() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        listOf<Any>(192_000, 128_000, 96_000, 64_000, 128_000L).forEach { bitrate ->
            val request =
                AudioExtractRequest.parse(
                    validArguments(
                        destination = validDestination(treeUri = treeUri),
                        audio = validAudio(mode = "aac", bitrate = bitrate),
                    ),
                )

            assertEquals(treeUri, request.outputTreeUri)
            assertEquals(AudioExtractMode.AAC, request.mode)
            assertEquals((bitrate as Number).toInt(), request.bitrate)
            assertEquals(treeUri, request.toChannelMap().destination()["treeUri"])
        }
    }

    @Test
    fun `rejects malformed roots and every exact-key violation`() {
        assertRejected(null)
        assertRejected("not a map")
        validArguments().keys.forEach { key ->
            assertRejected(validArguments().apply { remove(key) })
        }
        assertRejected(validArguments().apply { this["extra"] = null })
        assertRejected(validArguments().apply { this[1] = null })
    }

    @Test
    fun `rejects invalid content URIs including blank control and overlong values`() {
        listOf<Any?>(
            null,
            7,
            "",
            "   ",
            "content://",
            "content:///missing-authority",
            "content://bad authority/video",
            "content://media/video\u0000bad",
            "file:///sdcard/source.mp4",
            "https://example.test/source.mp4",
            "content://media/" + "x".repeat(4_100),
        ).forEach { value ->
            assertRejected(validArguments().apply { this["uri"] = value })
        }

        listOf<Any?>(
            7,
            "",
            "   ",
            "content://",
            "content://bad authority/tree",
            "content://documents/tree\u007fbad",
            "file:///sdcard/Music",
            "content://documents/" + "x".repeat(4_100),
        ).forEach { value ->
            assertRejected(
                validArguments(destination = validDestination(treeUri = value)),
            )
        }
    }

    @Test
    fun `accepts only safe bounded m4a output names`() {
        val accepted = listOf("audio.m4a", "课堂 音频.M4A", "..m4a")
        accepted.forEach { name ->
            assertEquals(
                name,
                AudioExtractRequest.parse(validArguments(outputFileName = name)).outputFileName,
            )
        }

        listOf<Any?>(
            null,
            7,
            "",
            "   ",
            ".",
            "..",
            "folder/output.m4a",
            "folder\\output.m4a",
            "../output.m4a",
            "bad:name.m4a",
            "bad?.m4a",
            "audio.mp4",
            "no-extension",
            ".m4a",
            "bad\u0000name.m4a",
            "trailing.m4a ",
            "界".repeat(100) + ".m4a",
            "x".repeat(252) + ".m4a",
        ).forEach { value ->
            assertRejected(validArguments(outputFileName = value))
        }
    }

    @Test
    fun `rejects malformed destinations and unsafe labels`() {
        assertRejected(validArguments(destination = null))
        assertRejected(validArguments(destination = "not a map"))
        validDestination().keys.forEach { key ->
            assertRejected(validArguments(destination = validDestination().apply { remove(key) }))
        }
        assertRejected(
            validArguments(destination = validDestination().apply { this["extra"] = null }),
        )
        assertRejected(validArguments(destination = validDestination().apply { this[1] = null }))

        listOf<Any?>(null, 7, "", "   ", "Music\u0000VideoSlim", "x".repeat(513)).forEach {
            label ->
            assertRejected(validArguments(destination = validDestination(label = label)))
        }
    }

    @Test
    fun `copy requires null bitrate and AAC requires an allowlisted channel integer`() {
        assertEquals(
            AudioExtractMode.COPY,
            AudioExtractRequest.parse(validArguments(audio = validAudio("copy", null))).mode,
        )

        listOf<Any?>(0, 64_000, 64_000L, true, 64_000.0, "64000").forEach { bitrate ->
            assertRejected(validArguments(audio = validAudio("copy", bitrate)))
        }

        listOf<Any?>(null, 0, -1, 63_999, 65_000, 256_000, true, "128000", 128_000.0).forEach {
            bitrate ->
            assertRejected(validArguments(audio = validAudio("aac", bitrate)))
        }
    }

    @Test
    fun `rejects malformed audio maps unknown modes and exact-key violations`() {
        assertRejected(validArguments(audio = null))
        assertRejected(validArguments(audio = "not a map"))
        validAudio().keys.forEach { key ->
            assertRejected(validArguments(audio = validAudio().apply { remove(key) }))
        }
        assertRejected(validArguments(audio = validAudio().apply { this["extra"] = null }))
        assertRejected(validArguments(audio = validAudio().apply { this[1] = null }))

        listOf<Any?>(null, 1, true, "", "COPY", "reencode", "remove").forEach { mode ->
            assertRejected(validArguments(audio = validAudio(mode, null)))
        }
    }

    @Test
    fun `audio validation reports stable UNKNOWN and audio engine errors retain stable wires`() {
        val exception = rejected(validArguments(outputFileName = "unsafe.mp4"))
        assertEquals(EngineErrorCode.UNKNOWN, exception.error.code)
        assertTrue(exception.error.message.isNotBlank())

        val stableAudioErrors =
            mapOf(
                EngineErrorCode.AUDIO_TRACK_MISSING to "AUDIO_TRACK_MISSING",
                EngineErrorCode.AUDIO_COPY_UNSUPPORTED to "AUDIO_COPY_UNSUPPORTED",
                EngineErrorCode.AUDIO_CHANNEL_LAYOUT_UNSUPPORTED to
                    "AUDIO_CHANNEL_LAYOUT_UNSUPPORTED",
                EngineErrorCode.AUDIO_DECODING_FAILED to "AUDIO_DECODING_FAILED",
                EngineErrorCode.AUDIO_ENCODING_FAILED to "AUDIO_ENCODING_FAILED",
                EngineErrorCode.AUDIO_OUTPUT_INVALID to "AUDIO_OUTPUT_INVALID",
            )
        stableAudioErrors.forEach { (code, wire) ->
            assertEquals(wire, code.wireName)
            assertTrue(code.defaultMessage.isNotBlank())
        }

        // Existing M2 error wires remain unchanged.
        assertEquals("SOURCE_CORRUPTED", EngineErrorCode.SOURCE_CORRUPTED.wireName)
        assertEquals("VIDEO_ENCODING_FAILED", EngineErrorCode.VIDEO_ENCODING_FAILED.wireName)
        assertEquals("CANCELLED", EngineErrorCode.CANCELLED.wireName)
    }

    private fun assertRejected(arguments: Any?) {
        rejected(arguments)
    }

    private fun rejected(arguments: Any?): ProcessRequestException {
        try {
            AudioExtractRequest.parse(arguments)
            fail("Expected ProcessRequestException for $arguments")
        } catch (exception: ProcessRequestException) {
            return exception
        }
        error("unreachable")
    }

    private fun validArguments(
        outputFileName: Any? = "lecture_slim_20260720_115739.m4a",
        destination: Any? = validDestination(),
        audio: Any? = validAudio(),
    ): MutableMap<Any?, Any?> =
        linkedMapOf(
            "uri" to "content://media/external/video/media/42",
            "outputFileName" to outputFileName,
            "destination" to destination,
            "audio" to audio,
        )

    private fun validDestination(
        treeUri: Any? = null,
        label: Any? = "系统音频 > Music > VideoSlim",
    ): MutableMap<Any?, Any?> =
        linkedMapOf(
            "treeUri" to treeUri,
            "label" to label,
        )

    private fun validAudio(
        mode: Any? = "copy",
        bitrate: Any? = null,
    ): MutableMap<Any?, Any?> =
        linkedMapOf(
            "mode" to mode,
            "bitrate" to bitrate,
        )

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.destination(): Map<String, Any?> =
        getValue("destination") as Map<String, Any?>
}
