package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingNotificationTextTest {
    @Test
    fun `running notification is monotonic bounded and cancellable`() {
        val text = ProcessingNotificationText.from(snapshot(state = "running", percent = 37.6))

        assertEquals("正在压缩视频", text.title)
        assertEquals("已完成 38%", text.body)
        assertEquals(38, text.progress)
        assertTrue(text.ongoing)
        assertTrue(text.showCancel)
    }

    @Test
    fun `success notification names the public destination`() {
        val text =
            ProcessingNotificationText.from(
                snapshot(
                    state = "success",
                    percent = 100.0,
                    outputUri = "content://media/output/1",
                ),
            )

        assertEquals("压缩完成", text.title)
        assertEquals("已保存到 Movies/VideoSlim/source_slim.mp4", text.body)
        assertEquals(100, text.progress)
        assertFalse(text.ongoing)
        assertFalse(text.showCancel)
    }

    @Test
    fun `failed and cancelled states remain readable`() {
        val failed =
            ProcessingNotificationText.from(
                snapshot(
                    state = "failed",
                    percent = 42.0,
                    errorCode = "ENCODER_UNAVAILABLE",
                    errorMessage = "设备没有可用编码器",
                ),
            )
        val cancelled = ProcessingNotificationText.from(snapshot(state = "cancelled", percent = 22.0))

        assertEquals("压缩失败", failed.title)
        assertEquals("设备没有可用编码器", failed.body)
        assertEquals("任务已取消", cancelled.title)
        assertEquals("没有生成输出文件", cancelled.body)
    }

    private fun snapshot(
        state: String,
        percent: Double,
        outputUri: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) =
        TaskRuntimeSnapshot(
            taskId = "task-1",
            percent = percent,
            state = state,
            sourceUri = "content://media/source/1",
            outputFileName = "source_slim.mp4",
            startedAtEpochMs = 1_000L,
            outputUri = outputUri,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
}
