package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputMediaSpecTest {
    @Test
    fun `video and audio output specs remain disjoint`() {
        assertEquals(".mp4", OutputMediaKind.VIDEO_MP4.extension)
        assertEquals("video/mp4", OutputMediaKind.VIDEO_MP4.mimeType)
        assertEquals("Movies/VideoSlim/", OutputMediaKind.VIDEO_MP4.scopedRelativePath)
        assertEquals("Movies", OutputMediaKind.VIDEO_MP4.publicDirectory)

        assertEquals(".m4a", OutputMediaKind.AUDIO_M4A.extension)
        assertEquals("audio/mp4", OutputMediaKind.AUDIO_M4A.mimeType)
        assertEquals("Music/VideoSlim/", OutputMediaKind.AUDIO_M4A.scopedRelativePath)
        assertEquals("Music", OutputMediaKind.AUDIO_M4A.publicDirectory)
    }

    @Test
    fun `recovery V2 round trips audio media kind`() {
        val record = audioRecord()
        val encoded = TaskRecoveryCodec.encode(record)

        assertTrue(encoded.startsWith("version=2\n"))
        assertTrue(encoded.contains("\nmediaKind=AUDIO_M4A\n"))
        assertEquals(TaskRecoveryDecodeResult.Success(record), TaskRecoveryCodec.decode(encoded))
    }

    @Test
    fun `recovery V1 migrates to video media kind`() {
        val video = audioRecord().copy(
            mediaKind = OutputMediaKind.VIDEO_MP4,
            tempFileName = "123e4567-e89b-12d3-a456-426614174000.mp4",
            expectedOutputDisplayName = "lecture.mp4",
            actualOutputDisplayName = "lecture.mp4",
            mediaStoreUri = "content://media/external/video/media/42",
        )
        val v1 =
            TaskRecoveryCodec.encode(video)
                .replaceFirst("version=2", "version=1")
                .lineSequence()
                .filterNot { it.startsWith("mediaKind=") }
                .joinToString("\n")

        assertEquals(
            TaskRecoveryDecodeResult.Success(video.copy(journalVersion = 1)),
            TaskRecoveryCodec.decode(v1),
        )
    }

    @Test
    fun `recovery rejects cross kind names and URIs`() {
        val audio = audioRecord()

        assertTrue(
            TaskRecoveryCodec.decode(
                TaskRecoveryCodec.encode(audio.copy(expectedOutputDisplayName = "lecture.mp4")),
            ) is TaskRecoveryDecodeResult.Invalid,
        )
        assertTrue(
            TaskRecoveryCodec.decode(
                TaskRecoveryCodec.encode(
                    audio.copy(mediaStoreUri = "content://media/external/video/media/42"),
                ),
            ) is TaskRecoveryDecodeResult.Invalid,
        )
    }

    @Test
    fun `temp ownership accepts lowercase UUID mp4 and m4a only`() {
        assertTrue(isValidRecoveryTempFileName("123e4567-e89b-12d3-a456-426614174000.mp4"))
        assertTrue(isValidRecoveryTempFileName("123e4567-e89b-12d3-a456-426614174000.m4a"))
        assertFalse(isValidRecoveryTempFileName("123e4567-e89b-12d3-a456-426614174000.M4A"))
        assertFalse(isValidRecoveryTempFileName("../123e4567-e89b-12d3-a456-426614174000.m4a"))
    }

    private fun audioRecord() =
        TaskRecoveryRecord(
            taskId = "task-audio",
            stage = RecoveryStage.PUBLISHING,
            tempFileName = "123e4567-e89b-12d3-a456-426614174000.m4a",
            expectedOutputDisplayName = "lecture.m4a",
            actualOutputDisplayName = "lecture.m4a",
            mediaStoreUri = "content://media/external/audio/media/42",
            legacyOutputPath = null,
            startedAtEpochMs = 1_721_234_567_890L,
            mediaKind = OutputMediaKind.AUDIO_M4A,
        )
}
