package com.videoslim.videoslim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPublicationCopyTest {
    @Test
    fun `copies all bytes while publication remains active`() {
        val source = ByteArray(37) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied =
            copyPublicationBytes(ByteArrayInputStream(source), output, { false }, bufferSize = 8)

        assertArrayEquals(source, output.toByteArray())
        assertEquals(source.size.toLong(), copied)
    }

    @Test
    fun `cancellation stops between bounded copy chunks`() {
        val output = ByteArrayOutputStream()
        var checks = 0

        assertThrows(IOException::class.java) {
            copyPublicationBytes(
                ByteArrayInputStream(ByteArray(24)),
                output,
                shouldCancel = { ++checks > 1 },
                bufferSize = 8,
            )
        }

        assertEquals(8, output.size())
    }

    @Test
    fun `readback counting detects short exact and oversized provider outputs`() {
        assertEquals(
            7L,
            countPublicationBytes(
                ByteArrayInputStream(ByteArray(7)),
                shouldCancel = { false },
                stopAfterBytes = 9,
                bufferSize = 3,
            ),
        )
        requirePublishedByteCount(expectedBytes = 7, observedBytes = 7)
        assertThrows(IOException::class.java) {
            requirePublishedByteCount(expectedBytes = 8, observedBytes = 7)
        }
        assertThrows(IOException::class.java) {
            requirePublishedByteCount(expectedBytes = 6, observedBytes = 7)
        }
    }

    @Test
    fun `unknown descriptor length requires full bounded readback`() {
        var fullReadbackOpened = 0
        verifyPublicationCompleteness(
            expectedBytes = 8L,
            descriptorLength = -1L,
            queriedLength = null,
            requireReadback = true,
            shouldCancel = { false },
            openReadback = {
                fullReadbackOpened += 1
                ByteArrayInputStream(ByteArray(8))
            },
        )
        assertEquals(1, fullReadbackOpened)

        assertThrows(IOException::class.java) {
            verifyPublicationCompleteness(
                expectedBytes = 8L,
                descriptorLength = -1L,
                queriedLength = null,
                requireReadback = true,
                shouldCancel = { false },
                openReadback = { ByteArrayInputStream(ByteArray(7)) },
            )
        }
    }

    @Test
    fun `SAF readback ignores plausible metadata and rejects truncated provider bytes`() {
        assertThrows(IOException::class.java) {
            verifyPublicationCompleteness(
                expectedBytes = 8L,
                descriptorLength = 8L,
                queriedLength = 8L,
                requireReadback = true,
                shouldCancel = { false },
                openReadback = { ByteArrayInputStream(ByteArray(6)) },
            )
        }
    }

    @Test
    fun `legacy allocation observer records URI before target and stops after crash boundary`() {
        val target =
            PublicationTarget(
                mediaStoreUri = "content://media/external/video/media/42",
                actualDisplayName = "clip.mp4",
                canonicalLegacyOutputPath = "/storage/emulated/0/Movies/VideoSlim/clip.mp4",
            )
        val events = mutableListOf<String>()
        val allocated =
            notifyPublicationAllocation(
                observer = recordingObserver(events),
                publicationUri = target.mediaStoreUri,
                createTarget = {
                    events += "factory"
                    target
                },
                beforeTargetCallback = { events += "owned" },
            )
        assertEquals(target, allocated)
        assertEquals(listOf("uri", "factory", "owned", "target"), events)

        val crashEvents = mutableListOf<String>()
        val crashObserver =
            object : PublicationObserver {
                override fun onPublicationUriAllocated(publicationUri: String) {
                    crashEvents += "uri"
                    throw IOException("simulated process death after durable URI record")
                }

                override fun onPublicationTargetAllocated(target: PublicationTarget) {
                    crashEvents += "target"
                }

                override fun onPublicationCompleted(target: PublicationTarget) {
                    crashEvents += "completed"
                }

                override fun onPublicationDiscarding(target: PublicationTarget) {
                    crashEvents += "discarding"
                }
            }
        assertThrows(IOException::class.java) {
            notifyPublicationAllocation(
                observer = crashObserver,
                publicationUri = target.mediaStoreUri,
                createTarget = {
                    crashEvents += "factory"
                    target
                },
            )
        }
        assertEquals(listOf("uri"), crashEvents)
    }

    @Test
    fun `legacy path is deleted only after MediaStore row removal is confirmed`() {
        assertEquals(false, shouldDeleteLegacyPath(rowRemovalConfirmed = false))
        assertEquals(true, shouldDeleteLegacyPath(rowRemovalConfirmed = true))
    }

    private fun recordingObserver(events: MutableList<String>): PublicationObserver =
        object : PublicationObserver {
            override fun onPublicationUriAllocated(publicationUri: String) {
                events += "uri"
            }

            override fun onPublicationTargetAllocated(target: PublicationTarget) {
                events += "target"
            }

            override fun onPublicationCompleted(target: PublicationTarget) {
                events += "completed"
            }

            override fun onPublicationDiscarding(target: PublicationTarget) {
                events += "discarding"
            }
        }
}
