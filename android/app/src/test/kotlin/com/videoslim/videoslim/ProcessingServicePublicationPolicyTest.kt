package com.videoslim.videoslim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServicePublicationPolicyTest {
    @Test
    fun `normal publication completion commits published recovery`() {
        assertEquals(
            RecoveryStage.PUBLISHED,
            publicationCompletionRecoveryStage(cancellationRequested = false),
        )
    }

    @Test
    fun `accepted cancellation wins completion race and retains discard intent`() {
        assertEquals(
            RecoveryStage.DISCARDING,
            publicationCompletionRecoveryStage(cancellationRequested = true),
        )
    }

    @Test
    fun `real recovery observer enriches and copies legacy video and audio on API 26 through 28`() {
        for (apiLevel in 26..28) {
            for (kind in OutputMediaKind.entries) {
                val taskId = "123e4567-e89b-12d3-a456-42661417400$apiLevel-${kind.ordinal}"
                val displayName = if (kind == OutputMediaKind.VIDEO_MP4) "movie.mp4" else "song.m4a"
                val uri =
                    if (kind == OutputMediaKind.VIDEO_MP4) {
                        "content://media/external/video/media/$apiLevel"
                    } else {
                        "content://media/external/audio/media/$apiLevel"
                    }
                val directory = if (kind == OutputMediaKind.VIDEO_MP4) "Movies" else "Music"
                val path = "/storage/emulated/0/$directory/VideoSlim/$displayName"
                val journal = StatefulPublicationJournal(transformingRecord(taskId, displayName, kind))
                val observer =
                    RecoveryPublicationObserver(
                        journal = journal,
                        taskId = { taskId },
                        cancellationRequested = { false },
                    )
                val payload = ByteArray(37) { (it + apiLevel + kind.ordinal).toByte() }
                val published = ByteArrayOutputStream()

                val target =
                    notifyPublicationAllocation(
                        observer = observer,
                        publicationUri = uri,
                        createTarget = { PublicationTarget(uri, displayName, path, kind) },
                        beforeTargetCallback = {
                            assertEquals(RecoveryStage.ALLOCATED, journal.record.stage)
                            assertEquals(uri, journal.record.mediaStoreUri)
                            assertTrue(
                                TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(journal.record))
                                    is TaskRecoveryDecodeResult.Success,
                            )
                        },
                    )
                assertEquals(RecoveryStage.PUBLISHING, journal.record.stage)
                assertEquals(path, journal.record.legacyOutputPath)

                val copied =
                    copyPublicationBytes(
                        input = ByteArrayInputStream(payload),
                        output = published,
                        shouldCancel = { false },
                        bufferSize = 8,
                    )
                assertEquals(payload.size.toLong(), copied)
                assertArrayEquals(payload, published.toByteArray())
                observer.onPublicationCompleted(target)
                assertEquals(RecoveryStage.PUBLISHED, journal.record.stage)
            }
        }
    }

    @Test
    fun `cancellation between allocation and copy remains discarding through completion`() {
        val taskId = "123e4567-e89b-12d3-a456-426614174000"
        val uri = "content://media/external/audio/media/93"
        val target =
            PublicationTarget(
                uri,
                "song.m4a",
                "/storage/emulated/0/Music/VideoSlim/song.m4a",
                OutputMediaKind.AUDIO_M4A,
            )
        val journal =
            StatefulPublicationJournal(
                transformingRecord(taskId, "song.m4a", OutputMediaKind.AUDIO_M4A),
            )
        var cancellationRequested = false
        val observer =
            RecoveryPublicationObserver(
                journal = journal,
                taskId = { taskId },
                cancellationRequested = { cancellationRequested },
            )

        observer.onPublicationUriAllocated(uri)
        cancellationRequested = true
        observer.onPublicationTargetAllocated(target)
        assertEquals(RecoveryStage.DISCARDING, journal.record.stage)
        observer.onPublicationCompleted(target)
        assertEquals(RecoveryStage.DISCARDING, journal.record.stage)
    }

    @Test
    fun `allocated target enrichment rejects conflicting URI name and path identity`() {
        val taskId = "123e4567-e89b-12d3-a456-426614174000"
        val uri = "content://media/external/video/media/91"
        val allocated =
            withPublicationAllocation(
                transformingRecord(taskId, "movie.mp4", OutputMediaKind.VIDEO_MP4),
                uri,
            )

        assertThrows(IllegalStateException::class.java) {
            withPublicationTarget(
                allocated,
                "movie.mp4",
                "content://media/external/video/media/92",
                "/storage/emulated/0/Movies/VideoSlim/movie.mp4",
                OutputMediaKind.VIDEO_MP4,
            )
        }
        val enriched =
            withPublicationTarget(
                allocated,
                "movie.mp4",
                uri,
                "/storage/emulated/0/Movies/VideoSlim/movie.mp4",
                OutputMediaKind.VIDEO_MP4,
            )
        assertThrows(IllegalStateException::class.java) {
            withPublicationTarget(
                enriched,
                "different.mp4",
                uri,
                "/storage/emulated/0/Movies/VideoSlim/different.mp4",
                OutputMediaKind.VIDEO_MP4,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            withPublicationTarget(
                enriched,
                "movie.mp4",
                uri,
                "/storage/emulated/0/Music/VideoSlim/movie.mp4",
                OutputMediaKind.VIDEO_MP4,
            )
        }
    }

    @Test
    fun `process death immediately after legacy insert leaves a recoverable allocated record`() {
        val taskId = "123e4567-e89b-12d3-a456-426614174000"
        val uri = "content://media/external/video/media/91"
        val journal =
            StatefulPublicationJournal(
                transformingRecord(taskId, "movie.mp4", OutputMediaKind.VIDEO_MP4),
            )
        val observer =
            RecoveryPublicationObserver(
                journal = journal,
                taskId = { taskId },
                cancellationRequested = { false },
            )

        observer.onPublicationUriAllocated(uri)
        val crashSnapshot = journal.record

        assertEquals(RecoveryStage.ALLOCATED, crashSnapshot.stage)
        assertEquals(uri, crashSnapshot.mediaStoreUri)
        assertEquals(null, crashSnapshot.legacyOutputPath)
        assertTrue(requiresPublicationTransactionBoundary(crashSnapshot.stage))
        assertEquals(
            TaskRecoveryDecodeResult.Success(crashSnapshot),
            TaskRecoveryCodec.decode(TaskRecoveryCodec.encode(crashSnapshot)),
        )
    }

    private fun transformingRecord(
        taskId: String,
        displayName: String,
        kind: OutputMediaKind,
    ): TaskRecoveryRecord =
        TaskRecoveryRecord(
            taskId = taskId,
            stage = RecoveryStage.TRANSFORMING,
            tempFileName = "123e4567-e89b-12d3-a456-426614174000${kind.extension}",
            expectedOutputDisplayName = displayName,
            actualOutputDisplayName = null,
            mediaStoreUri = null,
            legacyOutputPath = null,
            startedAtEpochMs = 1L,
            mediaKind = kind,
        )

    private class StatefulPublicationJournal(
        var record: TaskRecoveryRecord,
    ) : PublicationRecoveryJournal {
        override fun recordPublicationAllocation(
            taskId: String,
            publicationUri: String,
        ): TaskRecoveryRecord {
            require(record.taskId == taskId)
            record = withPublicationAllocation(record, publicationUri)
            return record
        }

        override fun recordPublicationTarget(
            taskId: String,
            actualOutputDisplayName: String,
            mediaStoreUri: String,
            canonicalLegacyOutputPath: String?,
            mediaKind: OutputMediaKind,
        ): TaskRecoveryRecord {
            require(record.taskId == taskId)
            record =
                withPublicationTarget(
                    current = record,
                    actualOutputDisplayName = actualOutputDisplayName,
                    mediaStoreUri = mediaStoreUri,
                    canonicalLegacyOutputPath = canonicalLegacyOutputPath,
                    mediaKind = mediaKind,
                )
            return record
        }

        override fun markPublished(taskId: String): TaskRecoveryRecord = transition(taskId, RecoveryStage.PUBLISHED)

        override fun markDiscarding(taskId: String): TaskRecoveryRecord = transition(taskId, RecoveryStage.DISCARDING)

        private fun transition(taskId: String, next: RecoveryStage): TaskRecoveryRecord {
            require(record.taskId == taskId)
            require(isAllowedRecoveryTransition(record.stage, next))
            record = record.copy(stage = next)
            return record
        }
    }
}
