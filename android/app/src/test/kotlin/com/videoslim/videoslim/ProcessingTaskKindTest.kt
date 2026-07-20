package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessingTaskKindTest {
    @Test
    fun `service start requires the explicit reserved task kind`() {
        val video = snapshot(TaskKind.VIDEO_COMPRESSION)
        val audio = snapshot(TaskKind.AUDIO_EXTRACTION)

        assertEquals(
            TaskKind.VIDEO_COMPRESSION,
            validatedStartTaskKind(video, "video_compression"),
        )
        assertEquals(
            TaskKind.AUDIO_EXTRACTION,
            validatedStartTaskKind(audio, "audio_extraction"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validatedStartTaskKind(audio, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedStartTaskKind(audio, "video_compression")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedStartTaskKind(video, "unknown")
        }
    }

    private fun snapshot(taskKind: TaskKind) =
        TaskRuntimeSnapshot(
            taskId = "task",
            percent = 0.0,
            state = TaskRuntimeSnapshot.STATE_RUNNING,
            phase = TaskRuntimeSnapshot.PHASE_PREPARING,
            sourceUri = "content://media/source/1",
            outputFileName = if (taskKind == TaskKind.AUDIO_EXTRACTION) "output.m4a" else "output.mp4",
            startedAtEpochMs = 1L,
            taskKind = taskKind,
        )
}
