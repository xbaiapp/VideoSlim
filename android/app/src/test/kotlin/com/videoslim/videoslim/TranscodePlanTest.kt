package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscodePlanTest {
    @Test
    fun `scales landscape and portrait from display-oriented dimensions`() {
        val landscape =
            TranscodePlan.create(
                request = request(longEdge = 1_920),
                metadata = metadata(displayWidth = 3_840, displayHeight = 2_160),
                sdkInt = 35,
            )
        assertEquals(VideoDimensions(1_920, 1_080), landscape.outputDimensions)
        assertTrue(landscape.presentationRequired)

        val portrait =
            TranscodePlan.create(
                request = request(longEdge = 1_920),
                metadata =
                    metadata(
                        storageWidth = 3_840,
                        storageHeight = 2_160,
                        displayWidth = 2_160,
                        displayHeight = 3_840,
                        rotationDegrees = 90,
                    ),
                sdkInt = 35,
            )
        assertEquals(VideoDimensions(1_080, 1_920), portrait.outputDimensions)
        assertTrue(portrait.presentationRequired)
    }

    @Test
    fun `rounds a scaled short edge to an even dimension`() {
        val plan =
            TranscodePlan.create(
                request = request(longEdge = 854),
                metadata = metadata(displayWidth = 1_000, displayHeight = 501),
                sdkInt = 35,
            )

        assertEquals(VideoDimensions(854, 428), plan.outputDimensions)
        assertEquals(0, plan.outputDimensions.width % 2)
        assertEquals(0, plan.outputDimensions.height % 2)
    }

    @Test
    fun `does not upscale and preserves resolution when long edge is null`() {
        val smallerSource =
            TranscodePlan.create(
                request = request(longEdge = 854),
                metadata = metadata(displayWidth = 640, displayHeight = 360),
                sdkInt = 35,
            )
        assertEquals(VideoDimensions(640, 360), smallerSource.outputDimensions)
        assertFalse(smallerSource.presentationRequired)

        val unchanged =
            TranscodePlan.create(
                request = request(longEdge = null),
                metadata = metadata(displayWidth = 1_921, displayHeight = 1_081),
                sdkInt = 35,
            )
        assertEquals(VideoDimensions(1_921, 1_081), unchanged.outputDimensions)
        assertFalse(unchanged.presentationRequired)
    }

    @Test
    fun `rejects invalid display dimensions as a corrupted source`() {
        val exception =
            try {
                TranscodePlan.create(
                    request = request(longEdge = 1_280),
                    metadata = metadata(displayWidth = 0, displayHeight = 1_080),
                    sdkInt = 35,
                )
                fail("Expected TranscodePlanException")
                error("unreachable")
            } catch (error: TranscodePlanException) {
                error
            }

        assertEquals(EngineErrorCode.SOURCE_CORRUPTED, exception.failure.code)
        assertTrue(exception.failure.message.contains("分辨率"))
    }

    @Test
    fun `selects normal SDR and API29 OpenGL HDR tone mapping`() {
        assertEquals(
            HdrMode.SDR,
            TranscodePlan.create(request(), metadata(isHdr = false), sdkInt = 26).hdrMode,
        )
        assertEquals(
            HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            TranscodePlan.create(request(), metadata(isHdr = true), sdkInt = 29).hdrMode,
        )
        assertEquals(
            HdrMode.TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            TranscodePlan.create(request(), metadata(isHdr = true), sdkInt = 35).hdrMode,
        )
    }

    @Test
    fun `fails HDR below API29 with a readable stable error`() {
        val exception =
            try {
                TranscodePlan.create(request(), metadata(isHdr = true), sdkInt = 28)
                fail("Expected TranscodePlanException")
                error("unreachable")
            } catch (error: TranscodePlanException) {
                error
            }

        assertEquals(EngineErrorCode.UNKNOWN, exception.failure.code)
        assertTrue(exception.failure.message.contains("HDR"))
        assertTrue(exception.failure.message.contains("Android 10"))
    }

    @Test
    fun `space estimate includes copy reencode and removed audio`() {
        val metadata =
            metadata(
                durationMs = 10_000,
                fileSizeBytes = 8_000_000,
                audioMime = "audio/mp4a-latm",
                audioBitrate = 128_000,
            )

        val copy =
            TranscodePlan.create(
                request = request(videoBitrate = 2_000_000, audioMode = AudioMode.COPY),
                metadata = metadata,
                sdkInt = 35,
            ).storageEstimate
        assertEquals(2_500_000L, copy.videoBytes)
        assertEquals(160_000L, copy.audioBytes)

        val reencode =
            TranscodePlan.create(
                request =
                    request(
                        videoBitrate = 2_000_000,
                        audioMode = AudioMode.REENCODE,
                        audioBitrate = 192_000,
                    ),
                metadata = metadata,
                sdkInt = 35,
            ).storageEstimate
        assertEquals(240_000L, reencode.audioBytes)

        val remove =
            TranscodePlan.create(
                request = request(videoBitrate = 2_000_000, audioMode = AudioMode.REMOVE),
                metadata = metadata,
                sdkInt = 35,
            ).storageEstimate
        assertEquals(0L, remove.audioBytes)

        assertEquals(16L * 1_024L * 1_024L, copy.overheadBytes)
        assertEquals(copy.videoBytes + copy.audioBytes + copy.overheadBytes, copy.outputBytes)
        assertEquals(copy.outputBytes * 2L + 32L * 1_024L * 1_024L, copy.cacheRequiredBytes)
        assertEquals(copy.outputBytes + 32L * 1_024L * 1_024L, copy.publicRequiredBytes)
    }

    @Test
    fun `copy uses a conservative audio fallback and no source track uses zero`() {
        val unknownBitrate =
            TranscodePlan.create(
                request = request(audioMode = AudioMode.COPY),
                metadata = metadata(durationMs = 10_000, audioBitrate = null),
                sdkInt = 35,
            ).storageEstimate
        assertEquals(320_000L, unknownBitrate.audioBytes)

        val noAudio =
            TranscodePlan.create(
                request =
                    request(
                        audioMode = AudioMode.REENCODE,
                        audioBitrate = 192_000,
                    ),
                metadata = metadata(durationMs = 10_000, audioMime = null, audioBitrate = null),
                sdkInt = 35,
            ).storageEstimate
        assertEquals(0L, noAudio.audioBytes)
    }

    @Test
    fun `space estimate saturates instead of overflowing`() {
        val estimate =
            TranscodePlan.create(
                request = request(videoBitrate = Int.MAX_VALUE, audioMode = AudioMode.REMOVE),
                metadata = metadata(durationMs = Long.MAX_VALUE, fileSizeBytes = Long.MAX_VALUE),
                sdkInt = 35,
            ).storageEstimate

        assertEquals(Long.MAX_VALUE, estimate.videoBytes)
        assertEquals(Long.MAX_VALUE, estimate.outputBytes)
        assertEquals(Long.MAX_VALUE, estimate.cacheRequiredBytes)
        assertEquals(Long.MAX_VALUE, estimate.publicRequiredBytes)
    }

    private fun request(
        videoBitrate: Int = 2_500_000,
        longEdge: Int? = null,
        audioMode: AudioMode = AudioMode.COPY,
        audioBitrate: Int? = null,
    ): ProcessRequest =
        ProcessRequest(
            sourceUri = "content://media/external/video/media/42",
            outputFileName = "output.mp4",
            videoCodec = VideoCodec.HEVC,
            videoBitrate = videoBitrate,
            longEdge = longEdge,
            audioMode = audioMode,
            audioBitrate = audioBitrate,
        )

    private fun metadata(
        fileSizeBytes: Long = 20_000_000,
        durationMs: Long = 60_000,
        storageWidth: Int = 1_920,
        storageHeight: Int = 1_080,
        displayWidth: Int = storageWidth,
        displayHeight: Int = storageHeight,
        rotationDegrees: Int = 0,
        audioMime: String? = "audio/mp4a-latm",
        audioBitrate: Int? = 128_000,
        isHdr: Boolean = false,
    ): VideoMetadata =
        VideoMetadata(
            sourceUri = "content://media/external/video/media/42",
            fileName = "source.mp4",
            fileSizeBytes = fileSizeBytes,
            durationMs = durationMs,
            container = "video/mp4",
            videoMime = "video/avc",
            storageWidth = storageWidth,
            storageHeight = storageHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            rotationDegrees = rotationDegrees,
            frameRate = 30.0,
            videoBitrate = 4_000_000,
            audioMime = audioMime,
            audioChannels = if (audioMime == null) null else 2,
            audioSampleRate = if (audioMime == null) null else 48_000,
            audioBitrate = audioBitrate,
            isHdr = isHdr,
        )
}
