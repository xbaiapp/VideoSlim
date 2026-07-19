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

internal class MediaStoreSaver(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun publishVideo(tempFile: File, requestedName: String): String {
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            throw IOException("Temporary video output is missing or empty")
        }
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
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$OUTPUT_DIRECTORY",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outputUri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
        try {
            copyIntoUri(tempFile, outputUri)
            val completed = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            if (resolver.update(outputUri, completed, null, null) != 1) {
                throw IOException("Unable to publish pending MediaStore video")
            }
            return outputUri.toString()
        } catch (error: Throwable) {
            runCatching { resolver.delete(outputUri, null, null) }
            throw error
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
        val destination = uniqueDestination(directory, requestedName)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, destination.name)
            put(MediaStore.Video.Media.TITLE, destination.nameWithoutExtension)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            put(MediaStore.Video.Media.DATA, destination.absolutePath)
        }
        val outputUri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Legacy MediaStore insert returned null")
        try {
            copyIntoUri(tempFile, outputUri)
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

    private companion object {
        const val VIDEO_MIME_TYPE = "video/mp4"
        const val OUTPUT_DIRECTORY = "VideoSlim"
        const val WRITE_MODE = "w"
        const val MAX_COLLISION_SUFFIX = 9_999
    }
}
