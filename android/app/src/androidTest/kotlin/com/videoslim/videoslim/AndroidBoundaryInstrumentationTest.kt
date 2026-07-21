package com.videoslim.videoslim

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBoundaryInstrumentationTest {
    @Test
    fun mergedDebugManifestKeepsPermissionAndServiceBoundaries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = targetPackageInfo(context)
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
        val allowed =
            setOf(
                Manifest.permission.INTERNET, // Debug-only Flutter tooling.
                Manifest.permission.WRITE_EXTERNAL_STORAGE, // maxSdkVersion=28.
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE,
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
                Manifest.permission.WAKE_LOCK,
                "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )
        val forbidden =
            setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.REQUEST_INSTALL_PACKAGES",
            )

        assertTrue("Unexpected merged permissions: ${requested - allowed}", requested.all(allowed::contains))
        assertTrue("Forbidden merged permissions: ${requested intersect forbidden}", requested.intersect(forbidden).isEmpty())
        assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"))

        val processingComponent = ComponentName(context, ProcessingService::class.java)
        val processingService =
            packageInfo.services?.singleOrNull { service ->
                ComponentName(service.packageName, service.name) == processingComponent
            }
        assertNotNull("ProcessingService is missing from the merged manifest", processingService)
        requireNotNull(processingService)
        assertFalse("ProcessingService must never be exported", processingService.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                processingService.foregroundServiceType,
            )
        }
    }

    @Test
    fun applicationRecoveryUsesConcreteStorageAndReconcilesOnceAcrossConcurrentCallers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.applicationContext is VideoSlimApplication)

        val suffix = UUID.randomUUID().toString()
        val taskId = "instrumentation-$suffix"
        val tempFile = File(File(context.cacheDir, "transcode"), "$suffix.mp4")
        val store = TaskRecoveryStore(context)
        assertNull("Instrumentation requires a fresh recovery slot", store.load())
        assertTrue(tempFile.parentFile?.mkdirs() == true || tempFile.parentFile?.isDirectory == true)
        tempFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        store.begin(
            taskId = taskId,
            tempFileName = tempFile.name,
            expectedOutputDisplayName = "instrumentation-$suffix.mp4",
        )

        val executor = Executors.newFixedThreadPool(RECONCILE_CALLERS)
        val ready = CountDownLatch(RECONCILE_CALLERS)
        val start = CountDownLatch(1)
        try {
            val futures =
                (0 until RECONCILE_CALLERS).map {
                    executor.submit<CleanupReport> {
                        ready.countDown()
                        assertTrue("Concurrent reconciler did not start", start.await(10, TimeUnit.SECONDS))
                        OrphanCleanup(context, store).reconcile()
                    }
                }
            assertTrue("Reconciliation callers were not ready", ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val reports = futures.map { future -> future.get(30, TimeUnit.SECONDS) }

            assertFalse(tempFile.exists())
            assertNull(store.load())
            assertEquals(1, reports.sumOf(CleanupReport::tempFilesDeleted))
            assertEquals(1, reports.sumOf(CleanupReport::journalRecordsCleared))
            assertEquals(0, reports.sumOf(CleanupReport::failures))
        } finally {
            start.countDown()
            executor.shutdownNow()
            runCatching {
                if (store.load()?.taskId == taskId) store.clear(taskId)
            }
            tempFile.delete()
        }
    }

    @Test
    fun scopedMediaStoreRowCanBeWrittenReadAndStrictlyDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val displayName = "videoslim-instrumentation-${UUID.randomUUID()}.mp4"
        val relativePath = "${Environment.DIRECTORY_MOVIES}/VideoSlim/instrumentation/"
        val payload = ByteArray(4096) { index -> ((index * 31) % 251).toByte() }
        var uri: Uri? = null
        var testFailure: Throwable? = null

        try {
            uri =
                resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )
            assertNotNull("MediaStore insert returned null", uri)
            resolver.openOutputStream(uri!!, "w").use { output ->
                requireNotNull(output) { "MediaStore output stream was unavailable" }
                output.write(payload)
            }
            assertEquals(
                1,
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ),
            )

            resolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.SIZE,
                ),
                null,
                null,
                null,
            ).use { cursor ->
                requireNotNull(cursor) { "MediaStore query returned null" }
                assertTrue("Inserted MediaStore row was missing", cursor.moveToFirst())
                assertEquals(displayName, cursor.getString(0))
                assertEquals("video/mp4", cursor.getString(1))
                assertEquals(relativePath, cursor.getString(2))
                assertEquals(payload.size.toLong(), cursor.getLong(3))
                assertFalse("Exact URI unexpectedly returned multiple rows", cursor.moveToNext())
            }
            val readBack = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "MediaStore input stream was unavailable" }
                input.readBytes()
            }
            assertArrayEquals(payload, readBack)
        } catch (error: Throwable) {
            testFailure = error
        } finally {
            val cleanupFailure =
                runCatching {
                    uri?.let { insertedUri ->
                        resolver.delete(insertedUri, null, null)
                        resolver.query(
                            insertedUri,
                            arrayOf(MediaStore.MediaColumns._ID),
                            null,
                            null,
                            null,
                        ).use { cursor ->
                            requireNotNull(cursor) { "MediaStore cleanup verification returned null" }
                            assertFalse("Instrumentation MediaStore row leaked", cursor.moveToFirst())
                        }
                    }
                }.exceptionOrNull()
            val originalFailure = testFailure
            if (originalFailure != null) {
                cleanupFailure?.let(originalFailure::addSuppressed)
                throw originalFailure
            }
            if (cleanupFailure != null) throw cleanupFailure
        }
    }

    private fun targetPackageInfo(context: Context): PackageInfo {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }

    private companion object {
        const val RECONCILE_CALLERS = 4
    }
}
