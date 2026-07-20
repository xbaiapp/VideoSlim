package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream

data class PublicationTarget(
    val mediaStoreUri: String,
    val actualDisplayName: String,
    val canonicalLegacyOutputPath: String? = null,
    val mediaKind: OutputMediaKind = OutputMediaKind.VIDEO_MP4,
)

interface PublicationObserver {
    /** Called immediately after any public output allocation, before ownership-field queries. */
    fun onPublicationUriAllocated(publicationUri: String)

    /** Called synchronously immediately after the output row (and legacy path) is allocated. */
    fun onPublicationTargetAllocated(target: PublicationTarget)

    /** Called synchronously only after pending is cleared or the legacy copy is fully closed. */
    fun onPublicationCompleted(target: PublicationTarget)

    /** Called before a fully owned allocation is deleted after failure or cancellation. */
    fun onPublicationDiscarding(target: PublicationTarget)

    companion object {
        val NONE: PublicationObserver =
            object : PublicationObserver {
                override fun onPublicationUriAllocated(publicationUri: String) = Unit

                override fun onPublicationTargetAllocated(target: PublicationTarget) = Unit

                override fun onPublicationCompleted(target: PublicationTarget) = Unit

                override fun onPublicationDiscarding(target: PublicationTarget) = Unit
            }
    }
}

internal class MediaStoreSaver(
    context: Context,
    private val publicationObserver: PublicationObserver = PublicationObserver.NONE,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun publishVideo(
        tempFile: File,
        requestedName: String,
        outputTreeUri: String? = null,
        shouldCancel: () -> Boolean = { false },
    ): String =
        publishMedia(
            tempFile = tempFile,
            requestedName = requestedName,
            mediaKind = OutputMediaKind.VIDEO_MP4,
            outputTreeUri = outputTreeUri,
            shouldCancel = shouldCancel,
        )

    fun publishAudio(
        tempFile: File,
        requestedName: String,
        outputTreeUri: String? = null,
        shouldCancel: () -> Boolean = { false },
    ): String =
        publishMedia(
            tempFile = tempFile,
            requestedName = requestedName,
            mediaKind = OutputMediaKind.AUDIO_M4A,
            outputTreeUri = outputTreeUri,
            shouldCancel = shouldCancel,
        )

    fun publishMedia(
        tempFile: File,
        requestedName: String,
        mediaKind: OutputMediaKind,
        outputTreeUri: String? = null,
        shouldCancel: () -> Boolean = { false },
    ): String {
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            throw IOException("Temporary media output is missing or empty")
        }
        validateRequestedName(requestedName, mediaKind)
        return if (outputTreeUri != null) {
            publishDocumentTree(tempFile, requestedName, mediaKind, outputTreeUri, shouldCancel)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(tempFile, requestedName, mediaKind, shouldCancel)
        } else {
            publishLegacy(tempFile, requestedName, mediaKind, shouldCancel)
        }
    }

    fun validateOutputDestination(outputTreeUri: String?) {
        if (outputTreeUri == null) return
        val treeUri = runCatching { Uri.parse(outputTreeUri) }.getOrNull()
            ?: throw OutputPermissionException("Invalid output tree URI")
        if (
            !DocumentsContract.isTreeUri(treeUri) ||
            !hasPersistedReadPermission(treeUri) ||
            !hasPersistedWritePermission(treeUri)
        ) {
            throw OutputPermissionException("Persisted output-folder read/write permission is missing")
        }
        val rootUri =
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            }.getOrElse { throw OutputPermissionException("Output tree URI is unreadable", it) }
        try {
            val cursor =
                resolver.query(
                    rootUri,
                    arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                    null,
                    null,
                    null,
                ) ?: throw OutputPermissionException("Output folder is unavailable")
            cursor.use {
                if (!it.moveToFirst()) throw OutputPermissionException("Output folder is unavailable")
                if (it.isNull(0)) {
                    throw OutputPermissionException("Output folder capabilities are unavailable")
                }
                val flags = it.getInt(0)
                if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                    throw OutputPermissionException("Output folder does not allow new files")
                }
            }
        } catch (error: OutputPermissionException) {
            throw error
        } catch (error: Throwable) {
            throw OutputPermissionException("Output folder cannot be verified", error)
        }
    }

    fun deletePublished(uriString: String): Boolean =
        runCatching {
            val uri = Uri.parse(uriString)
            if (resolver.delete(uri, null, null) > 0) {
                true
            } else {
                !contentUriExists(uri)
            }
        }.getOrDefault(false)

    private fun publishDocumentTree(
        tempFile: File,
        requestedName: String,
        mediaKind: OutputMediaKind,
        outputTreeUri: String,
        shouldCancel: () -> Boolean,
    ): String {
        validateOutputDestination(outputTreeUri)
        val treeUri = Uri.parse(outputTreeUri)
        val rootUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val outputUri =
            try {
                DocumentsContract.createDocument(resolver, rootUri, mediaKind.mimeType, requestedName)
                    ?: throw IOException("Document provider could not create the output media")
            } catch (error: SecurityException) {
                throw OutputPermissionException("Output-folder permission was lost", error)
            }
        var target: PublicationTarget? = null
        try {
            publicationObserver.onPublicationUriAllocated(outputUri.toString())
            val allocatedTarget = readDocumentPublicationTarget(outputUri, mediaKind)
            target = allocatedTarget
            publicationObserver.onPublicationTargetAllocated(allocatedTarget)
            copyIntoUri(tempFile, outputUri, shouldCancel)
            verifyPublishedLength(
                outputUri,
                tempFile.length(),
                shouldCancel,
                requireReadback = true,
            )
            publicationObserver.onPublicationCompleted(allocatedTarget)
            return outputUri.toString()
        } catch (error: Throwable) {
            target?.let { runCatching { publicationObserver.onPublicationDiscarding(it) } }
            val cleanupConfirmed = deletePublished(outputUri.toString())
            val reportedError =
                if (error is SecurityException || !hasPersistedWritePermission(treeUri)) {
                    OutputPermissionException("Output-folder permission was lost", error)
                } else {
                    error
                }
            throw PublicationCleanupException(cleanupConfirmed, reportedError)
        }
    }

    private fun readDocumentPublicationTarget(
        outputUri: Uri,
        mediaKind: OutputMediaKind,
    ): PublicationTarget {
        val cursor =
            resolver.query(
                outputUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: throw IOException("Created output document could not be queried")
        return cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) {
                throw IOException("Created output document has no display name")
            }
            val actualName = it.getString(0)
            validateRequestedName(actualName, mediaKind)
            PublicationTarget(
                mediaStoreUri = outputUri.toString(),
                actualDisplayName = actualName,
                mediaKind = mediaKind,
            )
        }
    }

    private fun publishScoped(
        tempFile: File,
        requestedName: String,
        mediaKind: OutputMediaKind,
        shouldCancel: () -> Boolean,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaKind.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, mediaKind.scopedRelativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outputUri =
            resolver.insert(externalContentUri(mediaKind), values)
                ?: throw IOException("MediaStore insert returned null")
        var target: PublicationTarget? = null
        try {
            publicationObserver.onPublicationUriAllocated(outputUri.toString())
            val allocatedTarget = readScopedPublicationTarget(outputUri, mediaKind)
            target = allocatedTarget
            publicationObserver.onPublicationTargetAllocated(allocatedTarget)
            copyIntoUri(tempFile, outputUri, shouldCancel)
            verifyPublishedLength(
                outputUri,
                tempFile.length(),
                shouldCancel,
                requireReadback = false,
            )
            val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(outputUri, completed, null, null) != 1) {
                throw IOException("Unable to publish pending MediaStore output")
            }
            publicationObserver.onPublicationCompleted(allocatedTarget)
            return outputUri.toString()
        } catch (error: Throwable) {
            target?.let { runCatching { publicationObserver.onPublicationDiscarding(it) } }
            val cleanupConfirmed = deletePublished(outputUri.toString())
            throw PublicationCleanupException(cleanupConfirmed, error)
        }
    }

    private fun readScopedPublicationTarget(
        outputUri: Uri,
        mediaKind: OutputMediaKind,
    ): PublicationTarget {
        val uriString = outputUri.toString()
        if (!OrphanCleanupPolicy.isAppMediaUri(mediaKind, uriString)) {
            throw IOException("MediaStore allocated an unsupported output URI")
        }
        val cursor =
            resolver.query(
                outputUri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING,
                ),
                null,
                null,
                null,
            ) ?: throw IOException("Allocated MediaStore output could not be queried")
        return cursor.use {
            if (!it.moveToFirst()) {
                throw IOException("Allocated MediaStore output is absent")
            }
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val pendingIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            if (it.isNull(nameIndex) || it.isNull(pathIndex) || it.isNull(pendingIndex)) {
                throw IOException("Allocated MediaStore output has incomplete ownership fields")
            }
            val actualName = it.getString(nameIndex)
            validateRequestedName(actualName, mediaKind)
            if (it.getString(pathIndex) != mediaKind.scopedRelativePath || it.getInt(pendingIndex) != 1) {
                throw IOException("Allocated MediaStore output has unexpected ownership fields")
            }
            PublicationTarget(
                mediaStoreUri = uriString,
                actualDisplayName = actualName,
                mediaKind = mediaKind,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        tempFile: File,
        requestedName: String,
        mediaKind: OutputMediaKind,
        shouldCancel: () -> Boolean,
    ): String {
        val directory =
            File(
                Environment.getExternalStoragePublicDirectory(
                    when (mediaKind) {
                        OutputMediaKind.VIDEO_MP4 -> Environment.DIRECTORY_MOVIES
                        OutputMediaKind.AUDIO_M4A -> Environment.DIRECTORY_MUSIC
                    },
                ),
                OUTPUT_DIRECTORY,
            )
        if (
            (!directory.exists() && !directory.mkdirs()) ||
            !directory.isDirectory
        ) {
            throw IOException("Unable to create public VideoSlim media directory")
        }
        val canonicalDirectory = directory.canonicalFile
        val destination = uniqueDestination(canonicalDirectory, requestedName).canonicalFile
        if (destination.parentFile != canonicalDirectory) {
            throw IOException("Legacy output escaped the VideoSlim directory")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, destination.name)
            put(MediaStore.MediaColumns.TITLE, destination.nameWithoutExtension)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaKind.mimeType)
            put(MediaStore.MediaColumns.DATA, destination.path)
        }
        val outputUri =
            resolver.insert(externalContentUri(mediaKind), values)
                ?: throw IOException("Legacy MediaStore insert returned null")
        var target: PublicationTarget? = null
        try {
            val allocatedTarget =
                notifyPublicationAllocation(
                    observer = publicationObserver,
                    publicationUri = outputUri.toString(),
                    createTarget = {
                        PublicationTarget(
                            mediaStoreUri = outputUri.toString(),
                            actualDisplayName = destination.name,
                            canonicalLegacyOutputPath = destination.path,
                            mediaKind = mediaKind,
                        )
                    },
                    beforeTargetCallback = { target = it },
                )
            copyIntoUri(tempFile, outputUri, shouldCancel)
            verifyPublishedLength(
                outputUri,
                tempFile.length(),
                shouldCancel,
                requireReadback = false,
            )
            publicationObserver.onPublicationCompleted(allocatedTarget)
            return outputUri.toString()
        } catch (error: Throwable) {
            target?.let { runCatching { publicationObserver.onPublicationDiscarding(it) } }
            val rowRemoved = deletePublished(outputUri.toString())
            val fileRemoved =
                if (shouldDeleteLegacyPath(rowRemoved)) {
                    runCatching {
                        !destination.exists() || destination.delete() || !destination.exists()
                    }.getOrDefault(false)
                } else {
                    false
                }
            throw PublicationCleanupException(rowRemoved && fileRemoved, error)
        }
    }

    private fun copyIntoUri(
        source: File,
        outputUri: Uri,
        shouldCancel: () -> Boolean,
    ) {
        val output =
            resolver.openOutputStream(outputUri, WRITE_MODE)
                ?: throw IOException("MediaStore output stream is unavailable")
        FileInputStream(source).use { input ->
            output.use { destination ->
                val copiedBytes = copyPublicationBytes(input, destination, shouldCancel)
                if (copiedBytes != source.length()) {
                    throw IOException(
                        "Published byte count $copiedBytes did not match source length ${source.length()}",
                    )
                }
            }
        }
    }

    private fun verifyPublishedLength(
        outputUri: Uri,
        expectedBytes: Long,
        shouldCancel: () -> Boolean,
        requireReadback: Boolean,
    ) {
        val descriptorLength =
            if (requireReadback) {
                null
            } else {
                runCatching {
                    resolver.openFileDescriptor(outputUri, "r")?.use { descriptor ->
                        descriptor.statSize
                    }
                }.getOrNull()
            }
        val queriedLength =
            if (!requireReadback && (descriptorLength == null || descriptorLength < 0L)) {
                runCatching {
                    resolver.query(
                        outputUri,
                        arrayOf(OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
                    }
                }.getOrNull()
            } else {
                null
            }
        verifyPublicationCompleteness(
            expectedBytes = expectedBytes,
            descriptorLength = descriptorLength,
            queriedLength = queriedLength,
            requireReadback = requireReadback,
            shouldCancel = shouldCancel,
            openReadback = { resolver.openInputStream(outputUri) },
        )
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

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }

    private fun hasPersistedWritePermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }

    private fun contentUriExists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { it.moveToFirst() }
            ?: true

    private fun externalContentUri(mediaKind: OutputMediaKind): Uri =
        when (mediaKind) {
            OutputMediaKind.VIDEO_MP4 -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            OutputMediaKind.AUDIO_M4A -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private fun validateRequestedName(
        name: String,
        mediaKind: OutputMediaKind,
    ) {
        if (!mediaKind.isSafeDisplayName(name)) {
            throw IOException("Output display name is not a safe ${mediaKind.extension} filename")
        }
    }

    private companion object {
        const val OUTPUT_DIRECTORY = "VideoSlim"
        const val WRITE_MODE = "w"
        const val MAX_COLLISION_SUFFIX = 9_999
    }
}

internal fun shouldDeleteLegacyPath(rowRemovalConfirmed: Boolean): Boolean = rowRemovalConfirmed

/**
 * The URI callback is the durable crash-recovery boundary and must always run
 * before target construction, the richer target callback, or subsequent I/O.
 */
internal fun notifyPublicationAllocation(
    observer: PublicationObserver,
    publicationUri: String,
    createTarget: () -> PublicationTarget,
    beforeTargetCallback: (PublicationTarget) -> Unit = {},
): PublicationTarget {
    observer.onPublicationUriAllocated(publicationUri)
    val target = createTarget()
    beforeTargetCallback(target)
    observer.onPublicationTargetAllocated(target)
    return target
}

internal fun verifyPublicationCompleteness(
    expectedBytes: Long,
    descriptorLength: Long?,
    queriedLength: Long?,
    requireReadback: Boolean,
    shouldCancel: () -> Boolean,
    openReadback: () -> InputStream?,
) {
    if (!requireReadback) {
        descriptorLength?.takeIf { it >= 0L }?.let { observed ->
            requirePublishedByteCount(expectedBytes, observed)
            return
        }
        queriedLength?.takeIf { it >= 0L }?.let { observed ->
            requirePublishedByteCount(expectedBytes, observed)
            return
        }
    }
    val input =
        openReadback()
            ?: throw IOException("Published output cannot be reopened for verification")
    val stopAfterBytes =
        if (expectedBytes == Long.MAX_VALUE) Long.MAX_VALUE else expectedBytes + 1L
    val observedBytes =
        input.use {
            countPublicationBytes(
                input = it,
                shouldCancel = shouldCancel,
                stopAfterBytes = stopAfterBytes,
            )
        }
    requirePublishedByteCount(expectedBytes, observedBytes)
}

internal fun copyPublicationBytes(
    input: InputStream,
    output: OutputStream,
    shouldCancel: () -> Boolean,
    bufferSize: Int = 1024 * 1024,
): Long {
    require(bufferSize > 0) { "bufferSize must be positive" }
    val buffer = ByteArray(bufferSize)
    var copiedBytes = 0L
    while (true) {
        if (shouldCancel() || Thread.currentThread().isInterrupted) {
            throw IOException("Video publication cancelled")
        }
        val count = input.read(buffer)
        if (count < 0) return copiedBytes
        if (count == 0) {
            val single = input.read()
            if (single < 0) return copiedBytes
            output.write(single)
            copiedBytes += 1L
            continue
        }
        output.write(buffer, 0, count)
        copiedBytes += count
    }
}

internal fun countPublicationBytes(
    input: InputStream,
    shouldCancel: () -> Boolean,
    stopAfterBytes: Long,
    bufferSize: Int = 1024 * 1024,
): Long {
    require(stopAfterBytes > 0L) { "stopAfterBytes must be positive" }
    require(bufferSize > 0) { "bufferSize must be positive" }
    val buffer = ByteArray(bufferSize)
    var count = 0L
    while (count < stopAfterBytes) {
        if (shouldCancel() || Thread.currentThread().isInterrupted) {
            throw IOException("Video publication verification cancelled")
        }
        val requested = minOf(buffer.size.toLong(), stopAfterBytes - count).toInt()
        val read = input.read(buffer, 0, requested)
        if (read < 0) return count
        if (read == 0) {
            val single = input.read()
            if (single < 0) return count
            count += 1L
            continue
        }
        count += read
    }
    return count
}

internal fun requirePublishedByteCount(
    expectedBytes: Long,
    observedBytes: Long,
) {
    if (expectedBytes <= 0L || observedBytes != expectedBytes) {
        throw IOException(
            "Published output length $observedBytes did not match source length $expectedBytes",
        )
    }
}

internal class OutputPermissionException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class PublicationCleanupException(
    val cleanupConfirmed: Boolean,
    cause: Throwable,
) : IOException("Video publication failed", cause)
