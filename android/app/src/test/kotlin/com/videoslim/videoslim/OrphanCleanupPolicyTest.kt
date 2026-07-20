package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanCleanupPolicyTest {
    @Test
    fun `cache policy deletes only uuid mp4 children and preserves active temp`() {
        val orphan = "123e4567-e89b-12d3-a456-426614174000.mp4"
        val active = "223e4567-e89b-12d3-a456-426614174000.mp4"

        assertEquals(CleanupAction.DELETE, OrphanCleanupPolicy.cacheAction(orphan, active))
        assertEquals(CleanupAction.KEEP, OrphanCleanupPolicy.cacheAction(active, active))
        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.cacheAction("../$orphan", active))
        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.cacheAction("notes.txt", active))
    }

    @Test
    fun `cache policy also recognizes owned UUID m4a children`() {
        val orphan = "323e4567-e89b-12d3-a456-426614174000.m4a"
        val active = "423e4567-e89b-12d3-a456-426614174000.m4a"

        assertEquals(CleanupAction.DELETE, OrphanCleanupPolicy.cacheAction(orphan, active))
        assertEquals(CleanupAction.KEEP, OrphanCleanupPolicy.cacheAction(active, active))
        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.cacheAction("../$orphan", active))
    }

    @Test
    fun `scoped media deletes exact pending app output only`() {
        val record = scopedRecord()
        val pending = scopedEntry(isPending = 1)

        assertEquals(CleanupAction.DELETE, scopedAction(record, pending))
        assertEquals(CleanupAction.KEEP, scopedAction(record, pending.copy(isPending = 0)))
        assertEquals(
            CleanupAction.DELETE,
            scopedAction(
                record.copy(stage = RecoveryStage.DISCARDING),
                pending.copy(isPending = 0),
            ),
        )
        assertEquals(CleanupAction.SKIP_UNSAFE, scopedAction(record, pending.copy(isPending = 2)))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(record, pending.copy(displayName = "someone-elses.mp4")),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(record, pending.copy(ownerPackageName = "com.example.other")),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(record, pending.copy(relativePath = "Movies/Other/")),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(
                record.copy(legacyOutputPath = "/storage/emulated/0/Movies/VideoSlim/lecture.mp4"),
                pending,
            ),
        )
    }

    @Test
    fun `unverified scoped allocation is never deletion authority`() {
        val record =
            scopedRecord().copy(
                stage = RecoveryStage.ALLOCATED,
                actualOutputDisplayName = null,
            )
        val pending = scopedEntry(isPending = 1)

        assertEquals(CleanupAction.SKIP_UNSAFE, scopedAction(record, pending))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(record, pending.copy(isPending = 0)),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(
                record,
                pending.copy(relativePath = "Movies/Other/"),
            ),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(record, pending.copy(ownerPackageName = "com.example.other")),
        )
    }

    @Test
    fun `scoped media rejects non app and malformed URIs`() {
        val entry = scopedEntry(isPending = 1)
        listOf(
            "content://downloads/public_downloads/42",
            "content://media/external/images/media/42",
            "content://media/external/video/media/not-a-number",
            "content://media/external/video/media/42/child",
            "file:///storage/emulated/0/Movies/VideoSlim/lecture.mp4",
        ).forEach { uri ->
            assertFalse(uri, OrphanCleanupPolicy.isAppMediaVideoUri(uri))
            assertEquals(
                CleanupAction.SKIP_UNSAFE,
                scopedAction(scopedRecord().copy(mediaStoreUri = uri), entry.copy(uri = uri)),
            )
        }
    }

    @Test
    fun `scoped media absent row is already clean only for a safe record`() {
        assertEquals(CleanupAction.ALREADY_ABSENT, scopedAction(scopedRecord(), null))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(scopedRecord().copy(mediaStoreUri = "content://other/42"), null),
        )
    }

    @Test
    fun `audio cleanup requires exact audio collection path name and owner`() {
        val record =
            scopedRecord().copy(
                mediaKind = OutputMediaKind.AUDIO_M4A,
                tempFileName = "123e4567-e89b-12d3-a456-426614174000.m4a",
                expectedOutputDisplayName = "lecture.m4a",
                actualOutputDisplayName = "lecture.m4a",
                mediaStoreUri = "content://media/external/audio/media/42",
            )
        val entry =
            scopedEntry(isPending = 1).copy(
                uri = "content://media/external/audio/media/42",
                displayName = "lecture.m4a",
                relativePath = OutputMediaKind.AUDIO_M4A.scopedRelativePath,
            )

        assertTrue(
            OrphanCleanupPolicy.isAppMediaUri(
                OutputMediaKind.AUDIO_M4A,
                requireNotNull(record.mediaStoreUri),
            ),
        )
        assertEquals(CleanupAction.DELETE, scopedAction(record, entry))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(record, entry.copy(relativePath = OutputMediaKind.VIDEO_MP4.scopedRelativePath)),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            scopedAction(
                record.copy(mediaStoreUri = "content://media/external/video/media/42"),
                entry.copy(uri = "content://media/external/video/media/42"),
            ),
        )
    }

    @Test
    fun `unverified SAF allocation is never deletion authority`() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMovies/" +
                "document/primary%3AMovies%2FVideoSlim%2Flecture.mp4"
        val record =
            scopedRecord().copy(
                stage = RecoveryStage.ALLOCATED,
                actualOutputDisplayName = null,
                mediaStoreUri = uri,
            )
        val observed = DocumentOutputEntry(uri = uri, displayName = "lecture.mp4")

        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.documentAction(record, observed))
        assertEquals(CleanupAction.ALREADY_ABSENT, OrphanCleanupPolicy.documentAction(record, null))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.documentAction(record, observed.copy(uri = "$uri-other")),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.documentAction(record, observed.copy(displayName = "../victim.mp4")),
        )
    }

    @Test
    fun `completed SAF document is preserved while incomplete extant document is quarantined`() {
        val uri = "content://provider.example/document/lecture"
        val record =
            scopedRecord().copy(
                mediaStoreUri = uri,
                actualOutputDisplayName = "lecture.mp4",
            )
        val observed = DocumentOutputEntry(uri = uri, displayName = "lecture.mp4")

        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.documentAction(record, observed))
        assertEquals(
            CleanupAction.KEEP,
            OrphanCleanupPolicy.documentAction(
                record.copy(stage = RecoveryStage.PUBLISHED),
                observed,
            ),
        )
    }

    @Test
    fun `SAF replacement at the same URI is never startup deletion authority`() {
        val uri = "content://provider.example/document/lecture"
        val record =
            scopedRecord().copy(
                stage = RecoveryStage.DISCARDING,
                mediaStoreUri = uri,
                actualOutputDisplayName = "lecture.mp4",
            )

        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.documentAction(
                record,
                DocumentOutputEntry(uri = uri, displayName = "lecture.mp4"),
            ),
        )
        assertEquals(CleanupAction.ALREADY_ABSENT, OrphanCleanupPolicy.documentAction(record, null))
    }

    @Test
    fun `legacy replacement path is quarantined after exact row cleanup`() {
        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.legacyPathAction(true))
        assertEquals(CleanupAction.ALREADY_ABSENT, OrphanCleanupPolicy.legacyPathAction(false))
    }

    @Test
    fun `V1 historical video name remains eligible for exact ownership checks`() {
        val historicalName = "a".repeat(241) + ".mp4"
        val root = "/storage/emulated/0/Movies/VideoSlim"
        val uri = "content://media/external/video/media/84"
        val record =
            scopedRecord().copy(
                expectedOutputDisplayName = historicalName,
                actualOutputDisplayName = historicalName,
                mediaStoreUri = uri,
                legacyOutputPath = "$root/$historicalName",
                journalVersion = 1,
            )

        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.legacyAction(record, root))
        assertTrue(
            OrphanCleanupPolicy.hasMatchingLegacyLocatorMetadata(
                record,
                root,
                LegacyMediaEntry(uri, historicalName, "$root/$historicalName"),
            ),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(record.copy(journalVersion = 2), root),
        )
    }

    @Test
    fun `legacy policy quarantines exact non-published app output and keeps published`() {
        val root = "/storage/emulated/0/Movies/VideoSlim"
        val record =
            scopedRecord().copy(
                legacyOutputPath = "$root/lecture.mp4",
                actualOutputDisplayName = "lecture.mp4",
            )

        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.legacyAction(record, root))
        assertEquals(
            CleanupAction.KEEP,
            OrphanCleanupPolicy.legacyAction(record.copy(stage = RecoveryStage.PUBLISHED), root),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(record.copy(legacyOutputPath = "$root/sub/lecture.mp4"), root),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(record.copy(legacyOutputPath = "$root/../Other/lecture.mp4"), root),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(record.copy(legacyOutputPath = "$root/other.mp4"), root),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(record.copy(mediaStoreUri = "content://downloads/42"), root),
        )
        val recordedUri = requireNotNull(record.mediaStoreUri)
        assertTrue(
            OrphanCleanupPolicy.hasMatchingLegacyLocatorMetadata(
                record,
                root,
                LegacyMediaEntry(
                    uri = recordedUri,
                    displayName = record.actualOutputDisplayName,
                    dataPath = record.legacyOutputPath,
                ),
            ),
        )
        assertFalse(
            OrphanCleanupPolicy.hasMatchingLegacyLocatorMetadata(
                record,
                root,
                LegacyMediaEntry(
                    uri = recordedUri,
                    displayName = record.actualOutputDisplayName,
                    dataPath = "$root/other.mp4",
                ),
            ),
        )
    }

    @Test
    fun `legacy replacement in place with identical row fields remains quarantined`() {
        val root = "/storage/emulated/0/Movies/VideoSlim"
        val uri = "content://media/external/video/media/42"
        val record =
            scopedRecord().copy(
                stage = RecoveryStage.DISCARDING,
                mediaStoreUri = uri,
                legacyOutputPath = "$root/lecture.mp4",
                actualOutputDisplayName = "lecture.mp4",
            )
        val replacementWithSameMetadata =
            LegacyMediaEntry(
                uri = uri,
                displayName = "lecture.mp4",
                dataPath = "$root/lecture.mp4",
            )

        // These legacy metadata fields cannot distinguish the interrupted bytes from a
        // replacement written in place after a crash.
        assertTrue(
            OrphanCleanupPolicy.hasMatchingLegacyLocatorMetadata(
                record,
                root,
                replacementWithSameMetadata,
            ),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(record, root),
        )
    }

    @Test
    fun `legacy audio replacement in place is quarantined for every pre-published stage`() {
        val root = "/storage/emulated/0/Music/VideoSlim"
        val record =
            scopedRecord().copy(
                mediaKind = OutputMediaKind.AUDIO_M4A,
                tempFileName = "323e4567-e89b-12d3-a456-426614174000.m4a",
                expectedOutputDisplayName = "lecture.m4a",
                actualOutputDisplayName = "lecture.m4a",
                mediaStoreUri = "content://media/external/audio/media/84",
                legacyOutputPath = "$root/lecture.m4a",
            )

        for (stage in listOf(RecoveryStage.PUBLISHING, RecoveryStage.DISCARDING)) {
            assertEquals(
                stage.name,
                CleanupAction.SKIP_UNSAFE,
                OrphanCleanupPolicy.legacyAction(record.copy(stage = stage), root),
            )
        }
    }

    @Test
    fun `legacy audio policy accepts only exact owned Music output`() {
        val root = "/storage/emulated/0/Music/VideoSlim"
        val uri = "content://media/external/audio/media/84"
        val record =
            scopedRecord().copy(
                tempFileName = "323e4567-e89b-12d3-a456-426614174000.m4a",
                expectedOutputDisplayName = "lecture.m4a",
                actualOutputDisplayName = "lecture.m4a",
                mediaStoreUri = uri,
                legacyOutputPath = "$root/lecture.m4a",
                mediaKind = OutputMediaKind.AUDIO_M4A,
            )

        assertTrue(OrphanCleanupPolicy.isAppMediaUri(OutputMediaKind.AUDIO_M4A, uri))
        assertFalse(OrphanCleanupPolicy.isAppMediaVideoUri(uri))
        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.legacyAction(record, root))
        assertTrue(
            OrphanCleanupPolicy.hasMatchingLegacyLocatorMetadata(
                record,
                root,
                LegacyMediaEntry(uri, "lecture.m4a", "$root/lecture.m4a"),
            ),
        )
        assertFalse(
            OrphanCleanupPolicy.hasMatchingLegacyLocatorMetadata(
                record,
                root,
                LegacyMediaEntry(
                    uri,
                    "lecture.m4a",
                    "/storage/emulated/0/Movies/VideoSlim/lecture.m4a",
                ),
            ),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.legacyAction(
                record.copy(mediaStoreUri = "content://media/external/video/media/84"),
                root,
            ),
        )
    }

    @Test
    fun `ten repeated cleanup decisions do not grow deletions`() {
        val record = scopedRecord()
        var observed: ScopedMediaEntry? = scopedEntry(isPending = 1)
        var deletions = 0

        repeat(10) {
            if (scopedAction(record, observed) == CleanupAction.DELETE) {
                deletions += 1
                observed = null
            }
        }

        assertEquals(1, deletions)
        assertEquals(CleanupAction.ALREADY_ABSENT, scopedAction(record, observed))
    }

    @Test
    fun `cleanup report exposes stable counts and bounded details`() {
        val report = CleanupReport()
        repeat(CleanupReport.MAX_DETAILS + 20) { report.addDetail("detail-$it") }
        report.tempFilesDeleted = 2
        report.outputsDeleted = 1
        report.outputsPreserved = 1
        report.unsafeItemsSkipped = 3
        report.failures = 4

        assertEquals(CleanupReport.MAX_DETAILS, report.details.size)
        assertTrue(report.summary().contains("tempDeleted=2"))
        assertTrue(report.summary().contains("failures=4"))
    }

    private fun scopedAction(
        record: TaskRecoveryRecord,
        observed: ScopedMediaEntry?,
    ) = OrphanCleanupPolicy.scopedAction(record, observed, APP_PACKAGE_NAME)

    private fun scopedRecord() =
        TaskRecoveryRecord(
            taskId = "task-42",
            stage = RecoveryStage.PUBLISHING,
            tempFileName = "123e4567-e89b-12d3-a456-426614174000.mp4",
            expectedOutputDisplayName = "lecture.mp4",
            actualOutputDisplayName = "lecture.mp4",
            mediaStoreUri = "content://media/external/video/media/42",
            legacyOutputPath = null,
            startedAtEpochMs = 1_721_234_567_890L,
        )

    private fun scopedEntry(isPending: Int) =
        ScopedMediaEntry(
            uri = "content://media/external/video/media/42",
            displayName = "lecture.mp4",
            relativePath = OrphanCleanupPolicy.SCOPED_RELATIVE_PATH,
            isPending = isPending,
            ownerPackageName = APP_PACKAGE_NAME,
        )

    private companion object {
        const val APP_PACKAGE_NAME = "com.videoslim.videoslim"
    }
}
