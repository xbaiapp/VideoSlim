package com.videoslim.videoslim

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineErrorMapperTest {
    @Test
    fun `maps Media3 decoder failures to source corrupted`() {
        listOf(3001, 3002, 3003).forEach { errorCode ->
            assertEquals(
                EngineErrorCode.SOURCE_CORRUPTED,
                EngineErrorMapper.fromExportErrorCode(errorCode).code,
            )
        }
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
    fun `keeps runtime encoding muxing IO and unknown export failures stable UNKNOWN`() {
        listOf(1000, 1001, 2000, 2005, 2006, 2008, 4002, 5001, 6001, 7001, 7002, 9999)
            .forEach { errorCode ->
                assertEquals(
                    EngineErrorCode.UNKNOWN,
                    EngineErrorMapper.fromExportErrorCode(errorCode).code,
                )
            }
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
                "outputUri" to "content://media/output/7",
                "errorCode" to null,
                "errorMessage" to null,
            ),
            EngineProgressEvent(
                taskId = "task-1",
                percent = 100.0,
                state = "success",
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
