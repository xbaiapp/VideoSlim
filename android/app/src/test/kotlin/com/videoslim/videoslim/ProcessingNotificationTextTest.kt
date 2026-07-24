package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingNotificationTextTest {
    @Test
    fun `encoding notification is bounded and cancellable`() {
        val text =
            ProcessingNotificationText.from(
                snapshot(
                    state = "running",
                    percent = 37.6,
                    phase = TaskRuntimeSnapshot.PHASE_ENCODING,
                ),
            )

        assertEquals("正在压缩视频", text.title)
        assertEquals("已完成 38% · 编码方式尚未确认", text.body)
        assertEquals(38, text.progress)
        assertTrue(text.ongoing)
        assertTrue(text.showCancel)
    }

    @Test
    fun `running phases use truthful plain language`() {
        val preparing =
            ProcessingNotificationText.from(
                snapshot("running", 0.0, TaskRuntimeSnapshot.PHASE_PREPARING),
            )
        val publishing =
            ProcessingNotificationText.from(
                snapshot("running", 99.0, TaskRuntimeSnapshot.PHASE_PUBLISHING),
            )
        val cancelling =
            ProcessingNotificationText.from(
                snapshot("running", 42.0, TaskRuntimeSnapshot.PHASE_CANCELLING),
            )
        val automaticRetry =
            ProcessingNotificationText.from(
                snapshot(
                    state = "running",
                    percent = 0.0,
                    phase = TaskRuntimeSnapshot.PHASE_PREPARING,
                    automaticSoftwareDecoderRetry = true,
                ),
            )

        assertEquals("正在准备视频", preparing.title)
        assertEquals("正在保存视频", publishing.title)
        assertTrue(publishing.body.contains("系统相册"))
        assertEquals("正在取消", cancelling.title)
        assertFalse(cancelling.showCancel)
        assertEquals("正在兼容重试视频", automaticRetry.title)
        assertTrue(automaticRetry.body.contains("已自动改用兼容方式"))
    }

    @Test
    fun `success notification names the user-visible destination`() {
        val text =
            ProcessingNotificationText.from(
                snapshot(
                    state = "success",
                    percent = 100.0,
                    phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                    outputUri = "content://media/output/1",
                ),
            )

        assertEquals("视频已压缩并保存", text.title)
        assertEquals(
            "已保存到 系统相册 > Movies > VideoSlim · 编码方式尚未确认",
            text.body,
        )
        assertEquals(100, text.progress)
        assertFalse(text.ongoing)
        assertFalse(text.showCancel)
    }

    @Test
    fun `failed notification never exposes raw technical errors`() {
        val failed =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 42.0,
                    phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                    errorCode = "UNKNOWN",
                    errorMessage = "Media3 ERROR_CODE 7001 CodecException",
                ),
            )
        val known =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 42.0,
                    phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                    errorCode = EngineErrorCode.VIDEO_ENCODING_FAILED.wireName,
                    errorMessage = "Media3 4002 encoder failed",
                ),
            )

        assertEquals("没能完成压缩", failed.title)
        assertEquals("处理失败，请打开 VideoSlim 查看详情 · 编码方式尚未确认", failed.body)
        assertTrue(known.body.contains("调整格式和画质"))
        listOf(failed.body, known.body).forEach { body ->
            assertFalse(body.contains("Media3"))
            assertFalse(body.contains("Codec"))
            assertFalse(body.contains("4002"))
            assertFalse(body.contains("7001"))
        }
    }

    @Test
    fun `decoder failure notification never points to the removed manual compatibility action`() {
        val hardware =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 80.0,
                    errorCode = EngineErrorCode.VIDEO_DECODING_FAILED.wireName,
                    videoDecoderMode = VideoDecoderMode.HARDWARE.wireName,
                ),
            )
        val software =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 80.0,
                    errorCode = EngineErrorCode.VIDEO_DECODING_FAILED.wireName,
                    videoDecoderMode = VideoDecoderMode.SOFTWARE.wireName,
                ),
            )

        assertFalse(hardware.body.contains("使用兼容模式重试"))
        assertTrue(hardware.body.contains("原视频没有被修改"))
        assertFalse(software.body.contains("使用兼容模式重试"))
        assertTrue(software.body.contains("软件读取方式未能完成"))
        assertTrue(software.body.contains("原视频没有被修改"))
    }

    @Test
    fun `capture metadata verification failure does not claim a saved output`() {
        val failed =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 99.0,
                    phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                    errorCode = EngineErrorCode.CAPTURE_METADATA_FAILED.wireName,
                ),
            )

        assertTrue(failed.body.contains("拍摄时间或位置"))
        assertTrue(failed.body.contains("没有保存"))
        assertFalse(failed.body.contains("已保存"))
    }

    @Test
    fun `invalid trim notification returns the user to the time editor`() {
        val failed =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 0.0,
                    errorCode = EngineErrorCode.INVALID_TRIM.wireName,
                ),
            )

        assertTrue(failed.body.contains("时间裁剪范围无效"))
        assertTrue(failed.body.contains("重新选择"))
    }

    @Test
    fun `cancelled state remains readable`() {
        val cancelled =
            ProcessingNotificationText.from(
                snapshot(
                    state = "cancelled",
                    percent = 22.0,
                    phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                ),
            )

        assertEquals("压缩已取消", cancelled.title)
        assertEquals("原视频没有被修改", cancelled.body)
    }

    @Test
    fun `audio notifications use extraction and conversion language without video encoding claims`() {
        val copy =
            ProcessingNotificationText.from(
                snapshot(
                    state = "running",
                    percent = 25.0,
                    taskKind = TaskKind.AUDIO_EXTRACTION,
                    audioMode = "copy",
                ),
            )
        val aac =
            ProcessingNotificationText.from(
                snapshot(
                    state = "running",
                    percent = 50.0,
                    taskKind = TaskKind.AUDIO_EXTRACTION,
                    audioMode = "aac",
                ),
            )
        val success =
            ProcessingNotificationText.from(
                snapshot(
                    state = "success",
                    percent = 100.0,
                    phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                    outputUri = "content://media/audio/1",
                    taskKind = TaskKind.AUDIO_EXTRACTION,
                    audioMode = "aac",
                ),
            )

        assertEquals("正在提取音频", copy.title)
        assertEquals("正在转换音频", aac.title)
        assertEquals("音频已提取并保存", success.title)
        listOf(copy.body, aac.body, success.body).forEach { body ->
            assertFalse(body.contains("视频压缩"))
            assertFalse(body.contains("编码方式"))
        }
    }

    @Test
    fun `audio copy failure guides AAC retry and audio cancellation is task correct`() {
        val failed =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 10.0,
                    taskKind = TaskKind.AUDIO_EXTRACTION,
                    audioMode = "copy",
                    errorCode = EngineErrorCode.AUDIO_COPY_UNSUPPORTED.wireName,
                ),
            )
        val cancelled =
            ProcessingNotificationText.from(
                snapshot(
                    state = "cancelled",
                    percent = 10.0,
                    taskKind = TaskKind.AUDIO_EXTRACTION,
                    audioMode = "copy",
                ),
            )

        assertEquals("没能完成音频提取", failed.title)
        assertTrue(failed.body.contains("改用 AAC 转码"))
        assertEquals("音频提取已取消", cancelled.title)
    }

    private fun snapshot(
        state: String,
        percent: Double,
        phase: String = TaskRuntimeSnapshot.PHASE_ENCODING,
        outputUri: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        videoDecoderMode: String = VideoDecoderMode.HARDWARE.wireName,
        automaticSoftwareDecoderRetry: Boolean = false,
        taskKind: TaskKind = TaskKind.VIDEO_COMPRESSION,
        audioMode: String? = null,
    ) =
        TaskRuntimeSnapshot(
            taskId = "task-1",
            percent = percent,
            state = state,
            phase = phase,
            sourceUri = "content://media/source/1",
            outputFileName = "source_slim.mp4",
            startedAtEpochMs = 1_000L,
            outputUri = outputUri,
            errorCode = errorCode,
            errorMessage = errorMessage,
            videoDecoderMode = videoDecoderMode,
            automaticSoftwareDecoderRetry = automaticSoftwareDecoderRetry,
            taskKind = taskKind,
            retryRequest =
                audioMode?.let { mode ->
                    mapOf("audio" to mapOf("mode" to mode, "bitrate" to null))
                },
        )
}
