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
    fun `scoped media deletes exact pending app output only`() {
        val record = scopedRecord()
        val pending = scopedEntry(isPending = 1)

        assertEquals(CleanupAction.DELETE, OrphanCleanupPolicy.scopedAction(record, pending))
        assertEquals(CleanupAction.KEEP, OrphanCleanupPolicy.scopedAction(record, pending.copy(isPending = 0)))
        assertEquals(
            CleanupAction.DELETE,
            OrphanCleanupPolicy.scopedAction(
                record.copy(stage = RecoveryStage.DISCARDING),
                pending.copy(isPending = 0),
            ),
        )
        assertEquals(CleanupAction.SKIP_UNSAFE, OrphanCleanupPolicy.scopedAction(record, pending.copy(isPending = 2)))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.scopedAction(record, pending.copy(displayName = "someone-elses.mp4")),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.scopedAction(record, pending.copy(relativePath = "Movies/Other/")),
        )
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.scopedAction(
                record.copy(legacyOutputPath = "/storage/emulated/0/Movies/VideoSlim/lecture.mp4"),
                pending,
            ),
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
                OrphanCleanupPolicy.scopedAction(scopedRecord().copy(mediaStoreUri = uri), entry.copy(uri = uri)),
            )
        }
    }

    @Test
    fun `scoped media absent row is already clean only for a safe record`() {
        assertEquals(CleanupAction.ALREADY_ABSENT, OrphanCleanupPolicy.scopedAction(scopedRecord(), null))
        assertEquals(
            CleanupAction.SKIP_UNSAFE,
            OrphanCleanupPolicy.scopedAction(scopedRecord().copy(mediaStoreUri = "content://other/42"), null),
        )
    }

    @Test
    fun `legacy policy deletes only direct canonical app output and keeps published`() {
        val root = "/storage/emulated/0/Movies/VideoSlim"
        val record =
            scopedRecord().copy(
                legacyOutputPath = "$root/lecture.mp4",
                actualOutputDisplayName = "lecture.mp4",
            )

        assertEquals(CleanupAction.DELETE, OrphanCleanupPolicy.legacyAction(record, root))
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
            OrphanCleanupPolicy.isOwnedLegacyEntry(
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
            OrphanCleanupPolicy.isOwnedLegacyEntry(
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
    fun `ten repeated cleanup decisions do not grow deletions`() {
        val record = scopedRecord()
        var observed: ScopedMediaEntry? = scopedEntry(isPending = 1)
        var deletions = 0

        repeat(10) {
            if (OrphanCleanupPolicy.scopedAction(record, observed) == CleanupAction.DELETE) {
                deletions += 1
                observed = null
            }
        }

        assertEquals(1, deletions)
        assertEquals(CleanupAction.ALREADY_ABSENT, OrphanCleanupPolicy.scopedAction(record, observed))
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
        )
}
