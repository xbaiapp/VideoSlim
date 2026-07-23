package com.videoslim.videoslim

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreCaptureDatePolicyTest {
    @Test
    fun `only video publication accepts a valid original capture time`() {
        val captureTime = Instant.parse("2026-07-22T10:30:15Z").toEpochMilli()

        assertEquals(
            captureTime,
            PublicationCaptureMetadataPolicy.dateTakenEpochMs(
                OutputMediaKind.VIDEO_MP4,
                captureTime,
            ),
        )
        assertNull(
            PublicationCaptureMetadataPolicy.dateTakenEpochMs(
                OutputMediaKind.AUDIO_M4A,
                captureTime,
            ),
        )
        assertNull(
            PublicationCaptureMetadataPolicy.dateTakenEpochMs(
                OutputMediaKind.VIDEO_MP4,
                0L,
            ),
        )
        assertNull(
            PublicationCaptureMetadataPolicy.dateTakenEpochMs(
                OutputMediaKind.VIDEO_MP4,
                null,
            ),
        )
    }

    @Test
    fun `scoped and legacy video inserts apply date taken while SAF remains byte copy only`() {
        val source = mediaStoreSaverSource()
        val scoped =
            source.substring(
                source.indexOf("private fun publishScoped"),
                source.indexOf("private fun readScopedPublicationTarget"),
            )
        val legacy =
            source.substring(
                source.indexOf("private fun publishLegacy"),
                source.indexOf("private fun copyIntoUri"),
            )
        val documentTree =
            source.substring(
                source.indexOf("private fun publishDocumentTree"),
                source.indexOf("private fun readDocumentPublicationTarget"),
            )

        assertTrue(scoped.contains("putVideoDateTaken(mediaKind, dateTakenEpochMs)"))
        assertTrue(legacy.contains("putVideoDateTaken(mediaKind, dateTakenEpochMs)"))
        assertTrue(!documentTree.contains("putVideoDateTaken"))
        assertTrue(source.contains("MediaStore.Video.VideoColumns.DATE_TAKEN"))
    }

    private fun mediaStoreSaverSource(): String {
        var directory = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        for (attempt in 0 until 10) {
            val candidates =
                listOf(
                    directory.resolve(
                        "app/src/main/kotlin/com/videoslim/videoslim/MediaStoreSaver.kt",
                    ),
                    directory.resolve(
                        "android/app/src/main/kotlin/com/videoslim/videoslim/MediaStoreSaver.kt",
                    ),
                )
            candidates.firstOrNull(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: break
        }
        error("Unable to locate MediaStoreSaver.kt")
    }
}
