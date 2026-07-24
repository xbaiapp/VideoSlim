package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticSoftwareDecoderRetryPolicyTest {
    private val hardwareRequest =
        ProcessRequest(
            sourceUri = "content://media/source/video",
            outputFileName = "source_slim.mp4",
            outputTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AExports",
            outputLocationLabel = "自定义文件夹 > Exports",
            videoCodec = VideoCodec.HEVC,
            videoDecoderMode = VideoDecoderMode.HARDWARE,
            videoBitrate = 1_111_111,
            longEdge = 1_280,
            crop = CropRect(left = 0, top = 0, width = 1_280, height = 720),
            trim = TimeTrim(startMs = 515_070L, endMs = 2_373_988L),
            audioMode = AudioMode.COPY,
            audioBitrate = null,
        )

    @Test
    fun `first hardware video decoding failure retries once with only decoder mode changed`() {
        val retry =
            AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                taskKind = TaskKind.VIDEO_COMPRESSION,
                currentRequest = hardwareRequest,
                automaticRetryAlreadyAttempted = false,
                cancellationRequested = false,
                forcedFinishSource = null,
                event = failedEvent(EngineErrorCode.VIDEO_DECODING_FAILED),
            )

        assertEquals(hardwareRequest.copy(videoDecoderMode = VideoDecoderMode.SOFTWARE), retry)
        assertEquals(hardwareRequest.toChannelMap()["uri"], retry?.toChannelMap()?.get("uri"))
        assertEquals(hardwareRequest.outputTreeUri, retry?.outputTreeUri)
        assertEquals(hardwareRequest.outputFileName, retry?.outputFileName)
        assertEquals(hardwareRequest.crop, retry?.crop)
        assertEquals(hardwareRequest.trim, retry?.trim)
        assertEquals(hardwareRequest.videoBitrate, retry?.videoBitrate)
        assertEquals(hardwareRequest.audioMode, retry?.audioMode)
    }

    @Test
    fun `software failure and an already consumed fallback cannot retry again`() {
        assertNull(
            AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                taskKind = TaskKind.VIDEO_COMPRESSION,
                currentRequest = hardwareRequest.copy(videoDecoderMode = VideoDecoderMode.SOFTWARE),
                automaticRetryAlreadyAttempted = false,
                cancellationRequested = false,
                forcedFinishSource = null,
                event = failedEvent(EngineErrorCode.VIDEO_DECODING_FAILED),
            ),
        )
        assertNull(
            AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                taskKind = TaskKind.VIDEO_COMPRESSION,
                currentRequest = hardwareRequest,
                automaticRetryAlreadyAttempted = true,
                cancellationRequested = false,
                forcedFinishSource = null,
                event = failedEvent(EngineErrorCode.VIDEO_DECODING_FAILED),
            ),
        )
    }

    @Test
    fun `encoding storage audio cancellation and service failures never trigger decoder fallback`() {
        for (code in EngineErrorCode.entries.filter { it != EngineErrorCode.VIDEO_DECODING_FAILED }) {
            assertNull(
                code.wireName,
                AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                    taskKind = TaskKind.VIDEO_COMPRESSION,
                    currentRequest = hardwareRequest,
                    automaticRetryAlreadyAttempted = false,
                    cancellationRequested = false,
                    forcedFinishSource = null,
                    event = failedEvent(code),
                ),
            )
        }
        assertNull(
            AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                taskKind = TaskKind.AUDIO_EXTRACTION,
                currentRequest = hardwareRequest,
                automaticRetryAlreadyAttempted = false,
                cancellationRequested = false,
                forcedFinishSource = null,
                event = failedEvent(EngineErrorCode.VIDEO_DECODING_FAILED),
            ),
        )
        assertNull(
            AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                taskKind = TaskKind.VIDEO_COMPRESSION,
                currentRequest = hardwareRequest,
                automaticRetryAlreadyAttempted = false,
                cancellationRequested = true,
                forcedFinishSource = null,
                event = failedEvent(EngineErrorCode.VIDEO_DECODING_FAILED),
            ),
        )
        assertNull(
            AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                taskKind = TaskKind.VIDEO_COMPRESSION,
                currentRequest = hardwareRequest,
                automaticRetryAlreadyAttempted = false,
                cancellationRequested = false,
                forcedFinishSource = ActiveTaskFinishSource.SERVICE_TIMEOUT,
                event = failedEvent(EngineErrorCode.VIDEO_DECODING_FAILED),
            ),
        )
    }

    @Test
    fun `running success and cancelled events never trigger fallback`() {
        for (state in listOf(TaskRuntimeSnapshot.STATE_RUNNING, TaskRuntimeSnapshot.STATE_SUCCESS, TaskRuntimeSnapshot.STATE_CANCELLED)) {
            assertNull(
                state,
                AutomaticSoftwareDecoderRetryPolicy.retryRequestOrNull(
                    taskKind = TaskKind.VIDEO_COMPRESSION,
                    currentRequest = hardwareRequest,
                    automaticRetryAlreadyAttempted = false,
                    cancellationRequested = false,
                    forcedFinishSource = null,
                    event =
                        EngineProgressEvent(
                            taskId = "engine-task",
                            percent = 5.0,
                            state = state,
                            phase = TaskRuntimeSnapshot.PHASE_ENCODING,
                            errorCode = EngineErrorCode.VIDEO_DECODING_FAILED.wireName,
                        ),
                ),
            )
        }
    }

    private fun failedEvent(code: EngineErrorCode) =
        EngineProgressEvent(
            taskId = "engine-task",
            percent = 5.0,
            state = TaskRuntimeSnapshot.STATE_FAILED,
            phase = TaskRuntimeSnapshot.PHASE_FINISHED,
            errorCode = code.wireName,
            errorMessage = code.defaultMessage,
        )
}
