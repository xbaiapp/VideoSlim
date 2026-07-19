package com.videoslim.videoslim

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineErrorMapperTest {
    @Test
    fun `maps Media3 decoder runtime failures without blaming the source`() {
        listOf(3001, 3002).forEach { errorCode ->
            assertEquals(
                EngineErrorCode.VIDEO_DECODING_FAILED,
                EngineErrorMapper.fromExportErrorCode(errorCode).code,
            )
        }
        assertEquals(
            EngineErrorCode.VIDEO_FORMAT_UNSUPPORTED,
            EngineErrorMapper.fromExportErrorCode(3003).code,
        )
    }

    @Test
    fun `maps Media3 encoder availability failures`() {
        listOf(4001, 4003).forEach { errorCode ->
            assertEquals(
                EngineErrorCode.ENCODER_UNAVAILABLE,
                EngineErrorMapper.fromExportErrorCode(errorCode).code,
            )
        }
    }

    @Test
    fun `maps runtime encoding failure separately from other unknown export failures`() {
        assertEquals(
            EngineErrorCode.VIDEO_ENCODING_FAILED,
            EngineErrorMapper.fromExportErrorCode(4002).code,
        )
        listOf(1000, 1001, 2000, 2005, 2006, 2008, 5001, 6001, 7001, 7002, 9999)
            .forEach { errorCode ->
                assertEquals(
                    EngineErrorCode.UNKNOWN,
                    EngineErrorMapper.fromExportErrorCode(errorCode).code,
                )
            }
    }

    @Test
    fun `HDR processing failures remain stable but explain tone mapping`() {
        val frameProcessing =
            EngineErrorMapper.fromExportErrorCode(
                errorCode = 5001,
                wasHdrToneMapping = true,
            )
        assertEquals(EngineErrorCode.UNKNOWN, frameProcessing.code)
        assertEquals("手机无法完成这个 HDR 视频的画面转换", frameProcessing.message)
        check(!frameProcessing.message.contains("Media3"))

        val encoderInit =
            EngineErrorMapper.fromExportErrorCode(
                errorCode = 4001,
                wasHdrToneMapping = true,
            )
        assertEquals(EngineErrorCode.ENCODER_UNAVAILABLE, encoderInit.code)
        check(encoderInit.message.contains("HDR"))

        assertEquals(
            EngineErrorCode.VIDEO_DECODING_FAILED.defaultMessage,
            EngineErrorMapper.fromExportErrorCode(
                errorCode = 3002,
                wasHdrToneMapping = true,
            ).message,
        )
    }

    @Test
    fun `maps cancellation and no-space throwable chains before generic errors`() {
        assertEquals(
            EngineErrorCode.CANCELLED,
            EngineErrorMapper.fromThrowable(CancellationException("cancelled")).code,
        )
        assertEquals(
            EngineErrorCode.INSUFFICIENT_STORAGE,
            EngineErrorMapper.fromThrowable(IOException("write failed: ENOSPC (No space left on device)"))
                .code,
        )
        assertEquals(
            EngineErrorCode.INSUFFICIENT_STORAGE,
            EngineErrorMapper.fromThrowable(
                IllegalStateException("publish failed", IOException("No space left on device")),
            ).code,
        )
        assertEquals(
            EngineErrorCode.UNKNOWN,
            EngineErrorMapper.fromThrowable(IOException("broken pipe")).code,
        )
    }

    @Test
    fun `progress event preserves the exact Dart wire map`() {
        assertEquals(
            linkedMapOf<String, Any?>(
                "taskId" to "task-1",
                "percent" to 100.0,
                "state" to "success",
                "phase" to "finished",
                "outputUri" to "content://media/output/7",
                "errorCode" to null,
                "errorMessage" to null,
            ),
            EngineProgressEvent(
                taskId = "task-1",
                percent = 100.0,
                state = "success",
                phase = "finished",
                outputUri = "content://media/output/7",
            ).toChannelMap(),
        )
    }

    @Test
    fun `every stable error has a readable Chinese message`() {
        EngineErrorCode.entries.forEach { code ->
            val failure = EngineFailure(code)
            assertEquals(code.defaultMessage, failure.message)
            check(failure.message.isNotBlank())
        }
    }
}
