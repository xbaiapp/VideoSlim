package com.videoslim.videoslim

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for Android lifecycle/threading seams that local JVM stubs cannot execute. */
class MediaIoRoutingPolicyTest {
    @Test
    fun `application owns one media dispatcher and activity lends it to every media channel`() {
        val application = source("VideoSlimApplication.kt")
        val activity = source("MainActivity.kt")

        assertTrue(application.contains("mediaIoDispatcher = AppMediaIoDispatcher()"))
        assertTrue(application.contains("mediaIoDispatcher.shutdown()"))
        assertEquals(
            3,
            Regex("ioDispatcher\\s*=\\s*app\\.mediaIoDispatcher")
                .findAll(activity)
                .count(),
        )
        val engineDispose = activity.indexOf("engineChannel?.dispose()")
        assertTrue(engineDispose >= 0)
        assertTrue(engineDispose < activity.indexOf("pendingNotificationPermission?.invoke(false)"))
        assertTrue(engineDispose < activity.indexOf("pendingLegacyPermission?.invoke(false)"))
    }

    @Test
    fun `engine metadata capabilities and destination preflight use bounded media io without activity executor ownership`() {
        val engine = source("EngineChannel.kt")
        val transcode = source("TranscodeEngine.kt")

        assertFalse(engine.contains("metadataExecutor"))
        assertFalse(engine.contains("Executors.newSingleThreadExecutor"))
        assertTrue(engine.contains("MediaIoOperation.VIDEO_METADATA"))
        assertTrue(engine.contains("MediaIoOperation.AUDIO_METADATA"))
        assertTrue(engine.contains("MediaIoOperation.ENCODER_CAPABILITIES"))
        assertTrue(engine.contains("MediaIoOperation.OUTPUT_DESTINATION_VALIDATION"))
        assertTrue(engine.contains("postToMain { continueAfterValidation(token) }"))
        assertTrue(engine.contains("activeLaunch = token"))
        assertTrue(
            engine.indexOf("activeLaunch = token") <
                engine.indexOf("MediaIoOperation.OUTPUT_DESTINATION_VALIDATION"),
        )
        val start =
            transcode.substring(
                transcode.indexOf("fun start(request: ProcessRequest"),
                transcode.indexOf("fun cancel(taskId: String)"),
            )
        assertFalse(start.contains("validateOutputDestination("))
        val prepare =
            transcode.substring(
                transcode.indexOf("private fun prepare(task: ActiveTask)"),
                transcode.indexOf("private fun beginTransformer(task: ActiveTask)"),
            )
        assertTrue(prepare.contains("EngineIoPreparationPolicy.prepare("))
        assertTrue(prepare.contains("validateOutputDestination(task.request.outputTreeUri)"))
    }

    @Test
    fun `picker store serializes preferences grants and commit before release`() {
        val picker = source("VideoPickerChannel.kt")
        val store = source("OutputLocationStore.kt")

        assertFalse(picker.contains("getSharedPreferences"))
        assertFalse(picker.contains("ContentResolver"))
        assertTrue(picker.contains("MediaIoOperation.VIDEO_GRANT_PERSISTENCE"))
        assertTrue(picker.contains("MediaIoOperation.OUTPUT_FOLDER_REPLACEMENT"))
        assertTrue(picker.contains("MediaIoOperation.OUTPUT_LOCATION_READ"))
        assertTrue(picker.contains("MediaIoOperation.OUTPUT_LOCATION_RESET"))

        val replacementCommit = store.indexOf(".putString(OUTPUT_LABEL_KEY, destination.label)")
        val replacementRelease = store.indexOf("releaseTreeGrant(Uri.parse(previous))")
        assertTrue(replacementCommit >= 0 && replacementCommit < replacementRelease)
        val recoveryPreparation = store.indexOf("recoveryStore.load()")
        val resetCommit = store.indexOf("preferences.edit().clear().commit()")
        val resetRelease = store.indexOf("previous?.let")
        assertTrue(recoveryPreparation >= 0 && recoveryPreparation < resetCommit)
        assertTrue(resetCommit < resetRelease)

        val replacement =
            picker.substring(
                picker.indexOf("private fun completeOutputFolderRequest"),
                picker.indexOf("private fun resetOutputLocation"),
            )
        assertTrue(
            replacement.indexOf("outputLocationChangeGuard.replaceCustomFolder") <
                replacement.indexOf("MediaIoOperation.OUTPUT_FOLDER_REPLACEMENT"),
        )
        val reset =
            picker.substring(
                picker.indexOf("private fun resetOutputLocation"),
                picker.indexOf("private fun <T> submit"),
            )
        assertTrue(
            reset.indexOf("outputLocationChangeGuard.resetToDefault") <
                reset.indexOf("MediaIoOperation.OUTPUT_LOCATION_RESET"),
        )
    }

    @Test
    fun `media actions prepare providers on worker and launch activity only through main completion`() {
        val actions = source("MediaActionsChannel.kt")

        assertTrue(actions.contains("MediaIoOperation.MEDIA_OPEN_PREPARATION"))
        assertTrue(actions.contains("MediaIoOperation.MEDIA_SHARE_PREPARATION"))
        assertTrue(actions.contains("MediaIoOperation.MEDIA_DELETE_PREFLIGHT"))
        assertTrue(actions.contains("MediaIoOperation.MEDIA_DELETE_RETRY"))
        assertTrue(actions.contains("MediaStore.createDeleteRequest"))
        assertTrue(actions.contains("MediaStore.getMediaUri"))
        assertEquals(1, Regex("activity\\.startActivity\\(").findAll(actions).count())
        assertTrue(
            actions.indexOf("reply.complete {") < actions.indexOf("activity.startActivity(intent)"),
        )
        assertFalse(actions.contains("pendingDeleteResult?.success"))
    }

    @Test
    fun `log channel shares coordinator and publication keeps final destination validation`() {
        val logChannel = source("LogChannel.kt")
        val saver = source("MediaStoreSaver.kt")

        assertTrue(logChannel.contains("MethodChannelCompletionCoordinator"))
        assertFalse(logChannel.contains("LogChannelCompletionCoordinator"))
        val publication = saver.substring(saver.indexOf("private fun publishDocumentTree"))
        assertTrue(
            publication.indexOf("validateOutputDestination(outputTreeUri)") <
                publication.indexOf("DocumentsContract.createDocument"),
        )
    }

    private fun source(name: String): String =
        locateSourceRoot().resolve(name).readText()

    private fun locateSourceRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        repeat(10) {
            val candidates =
                listOf(
                    directory.resolve("app/src/main/kotlin/com/videoslim/videoslim"),
                    directory.resolve("android/app/src/main/kotlin/com/videoslim/videoslim"),
                )
            candidates.firstOrNull { it.resolve("MainActivity.kt").isFile }?.let { return it }
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate Android production Kotlin sources")
    }
}