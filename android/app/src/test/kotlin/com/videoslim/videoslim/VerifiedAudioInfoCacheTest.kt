package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VerifiedAudioInfoCacheTest {
    @Test
    fun `matching successful audio snapshot reuses verified metadata once with public identity`() {
        val cache = VerifiedAudioInfoCache()
        val outputUri = "content://media/external/audio/media/42"
        cache.store(outputUri, verifiedMetadata())
        val success = audioSnapshot(outputUri = outputUri)

        assertNull(
            takeVerifiedPublishedAudioInfo(
                outputUri = "content://media/external/audio/media/other",
                snapshot = success,
                cache = cache,
            ),
        )

        val response = takeVerifiedPublishedAudioInfo(outputUri, success, cache)

        assertEquals(
            mapOf(
                "uri" to outputUri,
                "fileName" to "actual output.m4a",
                "fileSizeBytes" to 62_252_066L,
                "durationMs" to 5_107_541L,
                "container" to "video/mp4",
                "audioCodec" to AudioOutputVerifier.AAC_MIME,
                "audioChannels" to 1,
                "audioSampleRate" to 48_000,
                "audioBitrate" to 96_000,
            ),
            response,
        )
        assertFalse(response!!.containsKey("sampleDigest"))
        assertFalse(response.containsKey("sampleCount"))
        assertNull(takeVerifiedPublishedAudioInfo(outputUri, success, cache))
    }

    @Test
    fun `nonterminal video and mismatched snapshots cannot consume verified audio metadata`() {
        val cache = VerifiedAudioInfoCache()
        val outputUri = "content://media/external/audio/media/42"
        cache.store(outputUri, verifiedMetadata())

        assertNull(
            takeVerifiedPublishedAudioInfo(
                outputUri,
                audioSnapshot(outputUri = outputUri).copy(state = TaskRuntimeSnapshot.STATE_RUNNING),
                cache,
            ),
        )
        assertNull(
            takeVerifiedPublishedAudioInfo(
                outputUri,
                audioSnapshot(outputUri = outputUri).copy(taskKind = TaskKind.VIDEO_COMPRESSION),
                cache,
            ),
        )
        assertNull(
            takeVerifiedPublishedAudioInfo(
                outputUri,
                audioSnapshot(outputUri = "content://media/external/audio/media/other"),
                cache,
            ),
        )

        assertTrue(takeVerifiedPublishedAudioInfo(outputUri, audioSnapshot(outputUri), cache) != null)
    }

    @Test
    fun `engine caches only after cancellation checks and channel keeps physical fallback`() {
        val root = projectRoot()
        val engine =
            File(root, "src/main/kotlin/com/videoslim/videoslim/AudioExtractionEngine.kt").readText()
        val channel =
            File(root, "src/main/kotlin/com/videoslim/videoslim/EngineChannel.kt").readText()
        val terminalSection =
            engine.substring(
                engine.indexOf("private fun verifyAndPublish"),
                engine.indexOf("private fun scheduleProgress"),
            )
        val cacheStore = terminalSection.indexOf("verifiedAudioInfoCache.store")
        val finalCancellationCheck = terminalSection.lastIndexOf("if (task.cancelRequested")
        val successEmit =
            terminalSection.indexOf(
                "emit(task, 100.0, TaskRuntimeSnapshot.STATE_SUCCESS, outputUri = publishedUri)",
            )
        assertTrue(finalCancellationCheck >= 0)
        assertTrue(cacheStore > finalCancellationCheck)
        assertTrue(successEmit > cacheStore)

        val audioInfoSection =
            channel.substring(
                channel.indexOf("private fun getAudioInfo"),
                channel.indexOf("private fun getCapabilities"),
            )
        assertTrue(
            audioInfoSection.indexOf("takeVerifiedPublishedAudioInfo") <
                audioInfoSection.indexOf("submitIo"),
        )
        assertTrue(audioInfoSection.contains("audioMetadataReader.read(uri).toChannelMap()"))
    }

    private fun audioSnapshot(outputUri: String): TaskRuntimeSnapshot =
        TaskRuntimeSnapshot(
            taskKind = TaskKind.AUDIO_EXTRACTION,
            taskId = "audio-task",
            percent = 100.0,
            state = TaskRuntimeSnapshot.STATE_SUCCESS,
            phase = TaskRuntimeSnapshot.PHASE_FINISHED,
            sourceUri = "content://media/external/video/media/5039",
            outputFileName = "actual output.m4a",
            startedAtEpochMs = 1L,
            outputUri = outputUri,
        )

    private fun verifiedMetadata(): AudioMetadata =
        AudioMetadata(
            sourceUri = "/cache/random-temp.m4a",
            fileName = "random-temp.m4a",
            fileSizeBytes = 62_252_066L,
            durationMs = 5_107_541L,
            container = "video/mp4",
            audioMime = AudioOutputVerifier.AAC_MIME,
            audioChannels = 1,
            audioSampleRate = 48_000,
            audioBitrate = 96_000,
            audioTrackCount = 1,
            videoTrackCount = 0,
            firstSampleTimeUs = 0L,
            lastSampleTimeUs = 5_107_520_000L,
            sampleCount = 239_416L,
            sampleBytes = 61_000_000L,
            sampleDigest = AudioSampleDigest(AUDIO_SAMPLE_DIGEST_VERSION, "0".repeat(64)),
            usesIndexedPhysicalSampleSizes = true,
            sampleTimesMonotonic = true,
            maxSampleDeltaUs = 21_334L,
            audioProfile = AudioOutputVerifier.AAC_PROFILE_LC,
        )

    private fun projectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        repeat(8) {
            if (File(current, "src/main/kotlin/com/videoslim/videoslim/AudioExtractionEngine.kt").isFile) {
                return current
            }
            current = current.parentFile ?: error("Unable to locate Android app project root")
        }
        error("Unable to locate Android app project root")
    }
}
