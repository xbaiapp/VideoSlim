package com.videoslim.videoslim

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryCodecTest {
    @Test
    fun `active journal is not cleared when quarantine directory sync fails`() {
        val actions = mutableListOf<String>()

        assertThrows(IOException::class.java) {
            commitQuarantineBoundary(
                commitDestination = { actions += "destination" },
                syncQuarantineDirectory = {
                    actions += "quarantine-sync"
                    throw IOException("sync failed")
                },
                syncParentDirectory = { actions += "parent-sync" },
                clearActiveJournal = { actions += "clear" },
            )
        }

        assertEquals(listOf("destination", "quarantine-sync"), actions)
    }

    @Test
    fun `active journal is not cleared when quarantine parent sync fails`() {
        val actions = mutableListOf<String>()

        assertThrows(IOException::class.java) {
            commitQuarantineBoundary(
                commitDestination = { actions += "destination" },
                syncQuarantineDirectory = { actions += "quarantine-sync" },
                syncParentDirectory = {
                    actions += "parent-sync"
                    throw IOException("parent sync failed")
                },
                clearActiveJournal = { actions += "clear" },
            )
        }

        assertEquals(
            listOf("destination", "quarantine-sync", "parent-sync"),
            actions,
        )
    }

    @Test
    fun `round trips every recovery field`() {
        val record = completeRecord()

        val decoded = TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(record))

        assertEquals(TaskRecoveryDecodeResult.Success(record), decoded)
    }

    @Test
    fun `round trips nullable publication fields before allocation`() {
        val record =
            completeRecord().copy(
                stage = RecoveryStage.PREPARING,
                actualOutputDisplayName = null,
                mediaStoreUri = null,
                legacyOutputPath = null,
            )

        assertEquals(
            TaskRecoveryDecodeResult.Success(record),
            TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(record)),
        )
    }

    @Test
    fun `round trips an unverified scoped allocation`() {
        val record =
            completeRecord().copy(
                stage = RecoveryStage.ALLOCATED,
                actualOutputDisplayName = null,
                legacyOutputPath = null,
            )

        assertEquals(
            TaskRecoveryDecodeResult.Success(record),
            TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(record)),
        )
    }


    @Test
    fun `round trips an unverified SAF document allocation`() {
        val record =
            completeRecord().copy(
                stage = RecoveryStage.ALLOCATED,
                actualOutputDisplayName = null,
                mediaStoreUri =
                    "content://com.android.externalstorage.documents/tree/primary%3AMovies/" +
                        "document/primary%3AMovies%2FVideoSlim%2Flecture.mp4",
                legacyOutputPath = null,
            )

        assertEquals(
            TaskRecoveryDecodeResult.Success(record),
            TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(record)),
        )
    }

    @Test
    fun `rejects corrupt unknown incomplete and traversal records`() {
        val encoded = TaskRecoveryCodec.encode(completeRecord())
        val invalid =
            listOf(
                "",
                "not-a-recovery-record",
                encoded.replace("version=1", "version=99"),
                encoded.replace("stage=PUBLISHING", "stage=FUTURE_STAGE"),
                encoded.replace(Regex("(?m)^taskId=.*$"), "taskId=%%%"),
                encoded.lines().filterNot { it.startsWith("startedAtEpochMs=") }.joinToString("\n"),
                TaskRecoveryCodec.encode(completeRecord().copy(tempFileName = "../victim.mp4")),
                TaskRecoveryCodec.encode(completeRecord().copy(tempFileName = "folder\\victim.mp4")),
                TaskRecoveryCodec.encode(
                    completeRecord().copy(
                        stage = RecoveryStage.TRANSFORMING,
                        actualOutputDisplayName = null,
                    ),
                ),
                TaskRecoveryCodec.encode(
                    completeRecord().copy(
                        legacyOutputPath = "/storage/emulated/0/Movies/VideoSlim/other.mp4",
                    ),
                ),
            )

        invalid.forEach { raw ->
            assertTrue("Expected invalid result for $raw", TaskRecoveryCodec.decode(raw) is TaskRecoveryDecodeResult.Invalid)
        }
    }

    @Test
    fun `rejects publication fields outside their transaction stage`() {
        val publishingWithoutUri = completeRecord().copy(mediaStoreUri = null, legacyOutputPath = null)
        val transformingWithTarget = completeRecord().copy(stage = RecoveryStage.TRANSFORMING)
        val allocatedWithVerifiedName = completeRecord().copy(stage = RecoveryStage.ALLOCATED)
        val allocatedWithoutUri =
            completeRecord().copy(
                stage = RecoveryStage.ALLOCATED,
                actualOutputDisplayName = null,
                mediaStoreUri = null,
                legacyOutputPath = null,
            )

        listOf(
            publishingWithoutUri,
            transformingWithTarget,
            allocatedWithVerifiedName,
            allocatedWithoutUri,
        ).forEach { record ->
            assertTrue(
                TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(record)) is
                    TaskRecoveryDecodeResult.Invalid,
            )
        }
    }

    @Test
    fun `temp file policy accepts only a simple uuid mp4 child`() {
        assertTrue(isValidRecoveryTempFileName("123e4567-e89b-12d3-a456-426614174000.mp4"))
        listOf(
            "123e4567-e89b-12d3-a456-426614174000.MP4",
            "123e4567e89b12d3a456426614174000.mp4",
            "../123e4567-e89b-12d3-a456-426614174000.mp4",
            "/123e4567-e89b-12d3-a456-426614174000.mp4",
            "123e4567-e89b-12d3-a456-426614174000.mp4/other",
            "not-random.mp4",
        ).forEach { assertFalse(it, isValidRecoveryTempFileName(it)) }
    }

    @Test
    fun `stage transitions are monotonic and idempotent`() {
        RecoveryStage.entries.forEach { stage -> assertTrue(isAllowedRecoveryTransition(stage, stage)) }
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.PREPARING, RecoveryStage.TRANSFORMING))
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.TRANSFORMING, RecoveryStage.ALLOCATED))
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.ALLOCATED, RecoveryStage.PUBLISHING))
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.TRANSFORMING, RecoveryStage.PUBLISHING))
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.PUBLISHING, RecoveryStage.PUBLISHED))
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.PUBLISHING, RecoveryStage.DISCARDING))
        assertTrue(isAllowedRecoveryTransition(RecoveryStage.PUBLISHED, RecoveryStage.DISCARDING))
        assertFalse(isAllowedRecoveryTransition(RecoveryStage.PUBLISHED, RecoveryStage.PUBLISHING))
        assertFalse(isAllowedRecoveryTransition(RecoveryStage.DISCARDING, RecoveryStage.PUBLISHED))
        assertFalse(isAllowedRecoveryTransition(RecoveryStage.ALLOCATED, RecoveryStage.PUBLISHED))
        assertFalse(isAllowedRecoveryTransition(RecoveryStage.TRANSFORMING, RecoveryStage.PREPARING))
        assertFalse(isAllowedRecoveryTransition(RecoveryStage.PREPARING, RecoveryStage.PUBLISHED))
    }

    private fun completeRecord() =
        TaskRecoveryRecord(
            taskId = "task-42",
            stage = RecoveryStage.PUBLISHING,
            tempFileName = "123e4567-e89b-12d3-a456-426614174000.mp4",
            expectedOutputDisplayName = "lecture.mp4",
            actualOutputDisplayName = "lecture (1).mp4",
            mediaStoreUri = "content://media/external/video/media/42",
            legacyOutputPath = "/storage/emulated/0/Movies/VideoSlim/lecture (1).mp4",
            startedAtEpochMs = 1_721_234_567_890L,
        )
}
