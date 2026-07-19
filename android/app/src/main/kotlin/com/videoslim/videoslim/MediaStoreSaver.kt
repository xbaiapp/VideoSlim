package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class PublicationTarget(
    val mediaStoreUri: String,
    val actualDisplayName: String,
    val canonicalLegacyOutputPath: String? = null,
)

interface PublicationObserver {
    /** Called synchronously immediately after the output row (and legacy path) is allocated. */
    fun onPublicationTargetAllocated(target: PublicationTarget)

    /** Called synchronously only after pending is cleared or the legacy copy is fully closed. */
    fun onPublicationCompleted(target: PublicationTarget)

    companion object {
        val NONE: PublicationObserver =
            object : PublicationObserver {
                override fun onPublicationTargetAllocated(target: PublicationTarget) = Unit

                override fun onPublicationCompleted(target: PublicationTarget) = Unit
            }
    }
}

internal class MediaStoreSaver(
    context: Context,
    private val publicationObserver: PublicationObserver = PublicationObserver.NONE,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun publishVideo(tempFile: File, requestedName: String): String {
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            throw IOException("Temporary video output is missing or empty")
        }
        validateRequestedName(requestedName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(tempFile, requestedName)
        } else {
            publishLegacy(tempFile, requestedName)
        }
    }

    fun deletePublished(uriString: String) {
        runCatching { resolver.delete(Uri.parse(uriString), null, null) }
    }

    private fun publishScoped(tempFile: File, requestedName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, requestedName)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, SCOPED_RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outputUri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
        try {
            val target = readScopedPublicationTarget(outputUri)
            publicationObserver.onPublicationTargetAllocated(target)
            copyIntoUri(tempFile, outputUri)
            val completed = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            if (resolver.update(outputUri, completed, null, null) != 1) {
                throw IOException("Unable to publish pending MediaStore video")
            }
            publicationObserver.onPublicationCompleted(target)
            return outputUri.toString()
        } catch (error: Throwable) {
            runCatching { resolver.delete(outputUri, null, null) }
            throw error
        }
    }

    private fun readScopedPublicationTarget(outputUri: Uri): PublicationTarget {
        val uriString = outputUri.toString()
        if (!OrphanCleanupPolicy.isAppMediaVideoUri(uriString)) {
            throw IOException("MediaStore allocated an unsupported output URI")
        }
        val cursor =
            resolver.query(
                outputUri,
                arrayOf(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.IS_PENDING,
                ),
                null,
                null,
                null,
            ) ?: throw IOException("Allocated MediaStore output could not be queried")
        return cursor.use {
            if (!it.moveToFirst()) {
                throw IOException("Allocated MediaStore output is absent")
            }
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val pendingIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)
            if (it.isNull(nameIndex) || it.isNull(pathIndex) || it.isNull(pendingIndex)) {
                throw IOException("Allocated MediaStore output has incomplete ownership fields")
            }
            val actualName = it.getString(nameIndex)
            validateRequestedName(actualName)
            if (it.getString(pathIndex) != SCOPED_RELATIVE_PATH || it.getInt(pendingIndex) != 1) {
                throw IOException("Allocated MediaStore output has unexpected ownership fields")
            }
            PublicationTarget(
                mediaStoreUri = uriString,
                actualDisplayName = actualName,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(tempFile: File, requestedName: String): String {
        val directory =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                OUTPUT_DIRECTORY,
            )
        if (
            (!directory.exists() && !directory.mkdirs()) ||
            !directory.isDirectory
        ) {
            throw IOException("Unable to create public VideoSlim output directory")
        }
        val canonicalDirectory = directory.canonicalFile
        val destination = uniqueDestination(canonicalDirectory, requestedName).canonicalFile
        if (destination.parentFile != canonicalDirectory) {
            throw IOException("Legacy output escaped the VideoSlim directory")
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, destination.name)
            put(MediaStore.Video.Media.TITLE, destination.nameWithoutExtension)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            put(MediaStore.Video.Media.DATA, destination.path)
        }
        val outputUri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Legacy MediaStore insert returned null")
        val target =
            PublicationTarget(
                mediaStoreUri = outputUri.toString(),
                actualDisplayName = destination.name,
                canonicalLegacyOutputPath = destination.path,
            )
        try {
            publicationObserver.onPublicationTargetAllocated(target)
            copyIntoUri(tempFile, outputUri)
            publicationObserver.onPublicationCompleted(target)
            return outputUri.toString()
        } catch (error: Throwable) {
            runCatching { resolver.delete(outputUri, null, null) }
            runCatching { destination.delete() }
            throw error
        }
    }

    private fun copyIntoUri(source: File, outputUri: Uri) {
        val output =
            resolver.openOutputStream(outputUri, WRITE_MODE)
                ?: throw IOException("MediaStore output stream is unavailable")
        FileInputStream(source).use { input ->
            output.use { destination -> input.copyTo(destination) }
        }
    }

    private fun uniqueDestination(directory: File, requestedName: String): File {
        val requested = File(directory, requestedName)
        if (!requested.exists()) return requested
        val dot = requestedName.lastIndexOf('.')
        val stem = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (suffix in 1..MAX_COLLISION_SUFFIX) {
            val candidate = File(directory, "$stem ($suffix)$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Unable to allocate a unique output filename")
    }

    private fun validateRequestedName(name: String) {
        if (
            name.isBlank() ||
            name.length > MAX_OUTPUT_NAME_LENGTH ||
            !name.endsWith(MP4_EXTENSION, ignoreCase = true) ||
            name.substringBeforeLast('.', missingDelimiterValue = "").isBlank() ||
            '/' in name ||
            '\\' in name ||
            name.any { it.code < 0x20 || it.code == 0x7f }
        ) {
            throw IOException("Output display name is not a safe MP4 filename")
        }
    }

    private companion object {
        const val VIDEO_MIME_TYPE = "video/mp4"
        const val OUTPUT_DIRECTORY = "VideoSlim"
        const val SCOPED_RELATIVE_PATH = "Movies/VideoSlim/"
        const val WRITE_MODE = "w"
        const val MAX_COLLISION_SUFFIX = 9_999
        const val MAX_OUTPUT_NAME_LENGTH = 255
        const val MP4_EXTENSION = ".mp4"
    }
}
