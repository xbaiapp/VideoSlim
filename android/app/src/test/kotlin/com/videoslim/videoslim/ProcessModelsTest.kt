package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProcessModelsTest {
    @Test
    fun `parses the exact preview-frame request`() {
        assertEquals(
            PreviewFrameRequest(
                sourceUri = "content://media/external/video/media/42",
                timeMs = 12_345L,
            ),
            PreviewFrameRequest.parse(
                mapOf(
                    "uri" to "content://media/external/video/media/42",
                    "timeMs" to 12_345,
                ),
            ),
        )
        assertEquals(
            12_345L,
            PreviewFrameRequest.parse(
                mapOf(
                    "uri" to "content://media/external/video/media/42",
                    "timeMs" to 12_345L,
                ),
            ).timeMs,
        )
    }

    @Test
    fun `rejects malformed preview-frame requests`() {
        listOf<Any?>(
            null,
            emptyMap<String, Any?>(),
            mapOf("uri" to "file:///tmp/video.mp4", "timeMs" to 0),
            mapOf("uri" to "content://media/video/1", "timeMs" to -1),
            mapOf("uri" to "content://media/video/1", "timeMs" to 1.0),
            mapOf("uri" to "content://media/video/1", "timeMs" to 0, "extra" to true),
        ).forEach { request ->
            try {
                PreviewFrameRequest.parse(request)
                fail("Expected preview request rejection for $request")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `parses the exact M2 nested request`() {
        val request = ProcessRequest.parse(validArguments())

        assertEquals("content://media/external/video/media/42", request.sourceUri)
        assertEquals("lecture_slim.mp4", request.outputFileName)
        assertNull(request.outputTreeUri)
        assertEquals("系统相册 > Movies > VideoSlim", request.outputLocationLabel)
        assertEquals(VideoCodec.HEVC, request.videoCodec)
        assertEquals(VideoDecoderMode.HARDWARE, request.videoDecoderMode)
        assertEquals(2_500_000, request.videoBitrate)
        assertEquals(1_280, request.longEdge)
        assertEquals(AudioMode.COPY, request.audioMode)
        assertNull(request.audioBitrate)
    }

    @Test
    fun `accepts both M2 video codecs and positive channel integers`() {
        listOf(
            Triple("hevc", 1, VideoCodec.HEVC),
            Triple("h264", Int.MAX_VALUE, VideoCodec.H264),
            Triple("h264", 2_500_000L, VideoCodec.H264),
        ).forEach { (codec, bitrate, expectedCodec) ->
            val request =
                ProcessRequest.parse(
                    validArguments(
                        video =
                            validVideo().apply {
                                this["codec"] = codec
                                this["bitrate"] = bitrate
                            },
                    ),
                )

            assertEquals(expectedCodec, request.videoCodec)
            assertEquals((bitrate as Number).toInt(), request.videoBitrate)
        }
    }

    @Test
    fun `parses explicit software decoder compatibility mode`() {
        val software =
            ProcessRequest.parse(
                validArguments(
                    video = validVideo().apply { this["decoderMode"] = "software" },
                ),
            )
        assertEquals(VideoDecoderMode.SOFTWARE, software.videoDecoderMode)

        listOf<Any?>(null, "automatic", true).forEach { mode ->
            assertRejected(
                validArguments(video = validVideo().apply { this["decoderMode"] = mode }),
            )
        }
    }

    @Test
    fun `accepts only the M2 long-edge allowlist including channel longs`() {
        listOf<Any?>(null, 1_920, 1_280, 854, 1_920L).forEach { longEdge ->
            val request =
                ProcessRequest.parse(
                    validArguments(video = validVideo().apply { this["longEdge"] = longEdge }),
                )
            assertEquals((longEdge as? Number)?.toInt(), request.longEdge)
        }

        listOf<Any?>(0, -1, 853, 1_080, 1_921, "1280", 1_280.0, Long.MAX_VALUE).forEach {
            longEdge ->
            assertRejected(
                validArguments(video = validVideo().apply { this["longEdge"] = longEdge }),
            )
        }
    }

    @Test
    fun `parses copy reencode and remove audio policies`() {
        val copy = ProcessRequest.parse(validArguments(audio = validAudio("copy", null)))
        assertEquals(AudioMode.COPY, copy.audioMode)
        assertNull(copy.audioBitrate)

        listOf(192_000, 128_000, 96_000, 64_000, 128_000L).forEach { bitrate ->
            val request =
                ProcessRequest.parse(validArguments(audio = validAudio("reencode", bitrate)))
            assertEquals(AudioMode.REENCODE, request.audioMode)
            assertEquals((bitrate as Number).toInt(), request.audioBitrate)
        }

        val remove = ProcessRequest.parse(validArguments(audio = validAudio("remove", null)))
        assertEquals(AudioMode.REMOVE, remove.audioMode)
        assertNull(remove.audioBitrate)
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
        val invalidUris =
            listOf(
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

        val invalidNames =
            listOf(
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
    fun `rejects malformed video maps codecs and bitrates`() {
        assertRejected(validArguments().apply { this["video"] = null })
        assertRejected(validArguments().apply { this["video"] = "not a map" })
        validVideo().keys.forEach { key ->
            assertRejected(validArguments(video = validVideo().apply { remove(key) }))
        }
        assertRejected(validArguments(video = validVideo().apply { this["extra"] = null }))
        assertRejected(validArguments(video = validVideo().apply { this[1] = null }))

        listOf(null, 1, "HEVC", "av1").forEach { value ->
            assertRejected(validArguments(video = validVideo().apply { this["codec"] = value }))
        }
        listOf(null, "2500000", 0, -1, 2_500_000.0, Long.MAX_VALUE).forEach { value ->
            assertRejected(validArguments(video = validVideo().apply { this["bitrate"] = value }))
        }
    }

    @Test
    fun `M4-A accepts a strict crop and round-trips it for retry`() {
        val crop = linkedMapOf<String, Any?>(
            "left" to 12,
            "top" to 34L,
            "width" to 640,
            "height" to 480L,
        )
        val request =
            ProcessRequest.parse(
                validArguments(video = validVideo().apply { this["crop"] = crop }),
            )

        assertEquals(CropRect(left = 12, top = 34, width = 640, height = 480), request.crop)
        assertEquals(crop.mapValues { (_, value) -> (value as Number).toInt() }, request.toChannelMap()
            .let { it["video"] as Map<*, *> }["crop"])
        assertEquals(
            request,
            ProcessRequest.parse(request.toChannelMap()),
        )
    }

    @Test
    fun `M4-A crop validation is strict and uses INVALID_CROP`() {
        val invalidCrops =
            listOf<Any?>(
                "not-a-map",
                emptyMap<String, Any?>(),
                mapOf("left" to 0, "top" to 0, "width" to 100, "height" to 100, "extra" to 1),
                mapOf("left" to -1, "top" to 0, "width" to 100, "height" to 100),
                mapOf("left" to 0, "top" to 0, "width" to 0, "height" to 100),
                mapOf("left" to 0.0, "top" to 0, "width" to 100, "height" to 100),
            )
        invalidCrops.forEach { crop ->
            val exception =
                rejected(validArguments(video = validVideo().apply { this["crop"] = crop }))
            assertEquals(EngineErrorCode.INVALID_CROP, exception.error.code)
        }
    }

    @Test
    fun `M4-B accepts strict paired trim and round-trips it for recovery`() {
        listOf(2_000 to 8_000, 2_000L to 8_000L).forEach { (start, end) ->
            val request =
                ProcessRequest.parse(
                    validArguments(
                        video =
                            validVideo().apply {
                                this["trimStartMs"] = start
                                this["trimEndMs"] = end
                            },
                    ),
                )

            assertEquals(TimeTrim(startMs = 2_000L, endMs = 8_000L), request.trim)
            assertEquals(request, ProcessRequest.parse(request.toChannelMap()))
        }
    }

    @Test
    fun `M4-B trim validation is strict and uses INVALID_TRIM`() {
        val invalidPairs =
            listOf(
                0 to null,
                null to 10_000,
                -1 to 10_000,
                10_000 to 10_000,
                10_000 to 9_999,
                0 to 999,
                0.0 to 10_000,
                0 to "10000",
                true to 10_000,
            )
        invalidPairs.forEach { (start, end) ->
            val exception =
                rejected(
                    validArguments(
                        video =
                            validVideo().apply {
                                this["trimStartMs"] = start
                                this["trimEndMs"] = end
                            },
                    ),
                )
            assertEquals(EngineErrorCode.INVALID_TRIM, exception.error.code)
        }
    }

    @Test
    fun `rejects malformed audio maps modes bitrates and invalid combinations`() {
        assertRejected(validArguments().apply { this["audio"] = null })
        assertRejected(validArguments().apply { this["audio"] = "not a map" })
        validAudio().keys.forEach { key ->
            assertRejected(validArguments(audio = validAudio().apply { remove(key) }))
        }
        assertRejected(validArguments(audio = validAudio().apply { this["extra"] = null }))

        listOf(null, 1, "COPY", "aac").forEach { mode ->
            assertRejected(validArguments(audio = validAudio(mode, null)))
        }
        listOf(64_000, 64_000L, 0, "64000").forEach { bitrate ->
            assertRejected(validArguments(audio = validAudio("copy", bitrate)))
        }
        listOf(null, 0, -1, 63_999, 65_000, 256_000, "128000", 128_000.0).forEach { bitrate ->
            assertRejected(validArguments(audio = validAudio("reencode", bitrate)))
        }
        listOf(64_000, 64_000L, 0, "64000").forEach { bitrate ->
            assertRejected(validArguments(audio = validAudio("remove", bitrate)))
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
            "destination" to
                linkedMapOf(
                    "treeUri" to null,
                    "label" to "系统相册 > Movies > VideoSlim",
                ),
            "video" to video,
            "audio" to audio,
        )

    private fun validVideo(): MutableMap<Any?, Any?> =
        linkedMapOf(
            "codec" to "hevc",
            "decoderMode" to "hardware",
            "bitrate" to 2_500_000,
            "longEdge" to 1_280,
            "crop" to null,
            "trimStartMs" to null,
            "trimEndMs" to null,
        )

    private fun validAudio(
        mode: Any? = "copy",
        bitrate: Any? = null,
    ): MutableMap<Any?, Any?> =
        linkedMapOf(
            "mode" to mode,
            "bitrate" to bitrate,
        )
}
