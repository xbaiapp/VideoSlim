package com.videoslim.videoslim

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException

internal enum class SourceAccessStatus {
    READABLE,
    PERMISSION_DENIED,
    NOT_FOUND,
    PROVIDER_IO,
}

internal fun sourceAccessFailureFrom(error: Throwable): EngineFailure? {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    return when {
        causes.any { it is SecurityException } ->
            EngineFailure(EngineErrorCode.SOURCE_PERMISSION_LOST)
        causes.any { it is FileNotFoundException } ->
            EngineFailure(EngineErrorCode.SOURCE_UNAVAILABLE)
        causes.any { it is IOException } ->
            EngineFailure(EngineErrorCode.SOURCE_PROVIDER_FAILED)
        else -> null
    }
}

internal data class SourceAccessProbeResult(
    val status: SourceAccessStatus,
    val persistedReadPermission: Boolean,
    val statSize: Long? = null,
    val seekable: Boolean? = null,
    val bytesRead: Int? = null,
    val diagnostic: String? = null,
) {
    val isReadable: Boolean
        get() = status == SourceAccessStatus.READABLE

    fun toEngineFailure(): EngineFailure? =
        when (status) {
            SourceAccessStatus.READABLE -> null
            SourceAccessStatus.PERMISSION_DENIED ->
                EngineFailure(EngineErrorCode.SOURCE_PERMISSION_LOST)
            SourceAccessStatus.NOT_FOUND -> EngineFailure(EngineErrorCode.SOURCE_UNAVAILABLE)
            SourceAccessStatus.PROVIDER_IO -> EngineFailure(EngineErrorCode.SOURCE_PROVIDER_FAILED)
        }

    fun toFailureAtExport(baseline: SourceAccessProbeResult?): EngineFailure? {
        toEngineFailure()?.let { return it }
        val originalSize = baseline?.statSize
        val currentSize = statSize
        return if (originalSize != null && currentSize != null && originalSize != currentSize) {
            EngineFailure(
                EngineErrorCode.SOURCE_UNAVAILABLE,
                "所选视频在处理过程中发生了变化，请重新选择文件",
            )
        } else {
            null
        }
    }

    fun toLogString(): String =
        "status=${status.name} persistedRead=$persistedReadPermission " +
            "size=${statSize ?: "unknown"} seekable=${seekable ?: "unknown"} " +
            "bytesRead=${bytesRead ?: "unknown"} diagnostic=${diagnostic ?: "none"}"
}

internal class SourceAccessProbe(context: Context) {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    fun probe(uriString: String): SourceAccessProbeResult = probe(Uri.parse(uriString))

    fun probe(uri: Uri): SourceAccessProbeResult {
        val persistedRead = hasPersistedReadPermission(uri)
        return try {
            val descriptor = contentResolver.openFileDescriptor(uri, READ_MODE)
                ?: return SourceAccessProbeResult(
                    status = SourceAccessStatus.NOT_FOUND,
                    persistedReadPermission = persistedRead,
                    diagnostic = "null_descriptor",
                )
            val size = descriptor.statSize.takeIf { it >= 0L }
            val seekable =
                runCatching {
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
                    true
                }.getOrDefault(false)
            if (!seekable) {
                descriptor.close()
                return SourceAccessProbeResult(
                    status = SourceAccessStatus.PROVIDER_IO,
                    persistedReadPermission = persistedRead,
                    statSize = size,
                    seekable = false,
                    diagnostic = "unseekable_descriptor",
                )
            }
            val bytesRead =
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.read(ByteArray(PROBE_BYTES))
                }
            if (bytesRead < 0 && size != 0L) {
                return SourceAccessProbeResult(
                    status = SourceAccessStatus.PROVIDER_IO,
                    persistedReadPermission = persistedRead,
                    statSize = size,
                    seekable = true,
                    bytesRead = bytesRead,
                    diagnostic = "unexpected_end_of_file",
                )
            }
            SourceAccessProbeResult(
                status = SourceAccessStatus.READABLE,
                persistedReadPermission = persistedRead,
                statSize = size,
                seekable = true,
                bytesRead = bytesRead.coerceAtLeast(0),
            )
        } catch (error: SecurityException) {
            SourceAccessProbeResult(
                status = SourceAccessStatus.PERMISSION_DENIED,
                persistedReadPermission = persistedRead,
                diagnostic = error.javaClass.simpleName,
            )
        } catch (error: FileNotFoundException) {
            SourceAccessProbeResult(
                status = SourceAccessStatus.NOT_FOUND,
                persistedReadPermission = persistedRead,
                diagnostic = error.javaClass.simpleName,
            )
        } catch (error: IOException) {
            SourceAccessProbeResult(
                status = SourceAccessStatus.PROVIDER_IO,
                persistedReadPermission = persistedRead,
                diagnostic = error.javaClass.simpleName,
            )
        } catch (error: RuntimeException) {
            SourceAccessProbeResult(
                status = SourceAccessStatus.PROVIDER_IO,
                persistedReadPermission = persistedRead,
                diagnostic = error.javaClass.simpleName,
            )
        }
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        runCatching {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)

    private companion object {
        const val READ_MODE = "r"
        const val PROBE_BYTES = 16
    }
}
