package com.videoslim.videoslim

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.min

/** Blocking storage boundary owned by [AppLogDispatcher] in production. */
internal interface AppLogStorage {
    fun append(entry: String)

    fun readAll(): String

    fun createShareSnapshot(): File
}

/** Pure one-line normalization shared by queue admission and file storage. */
internal object AppLogEntryNormalizer {
    fun normalize(
        entry: String,
        byteLimit: Int,
    ): String {
        require(byteLimit > 0) { "byteLimit must be positive" }
        val singleLine = buildString(entry.length) {
            entry.forEach { character ->
                when (character) {
                    '\r' -> append("\\r")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
        }
        return truncateUtf8(singleLine, byteLimit)
    }

    fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private fun truncateUtf8(
        value: String,
        limit: Int,
    ): String {
        if (utf8Size(value) <= limit) return value

        val suffix = "..."
        val includeSuffix = limit >= utf8Size(suffix)
        val contentLimit = if (includeSuffix) limit - utf8Size(suffix) else limit
        val result = StringBuilder()
        var retainedBytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val character = String(Character.toChars(codePoint))
            val characterBytes = utf8Size(character)
            if (retainedBytes + characterBytes > contentLimit) break
            result.append(character)
            retainedBytes += characterBytes
            offset += Character.charCount(codePoint)
        }
        if (includeSuffix) result.append(suffix)
        return result.toString()
    }
}

/**
 * Blocking append-only log storage with count and UTF-8 byte rotation.
 *
 * The active file is always kept at filesDir/logs/videoslim.log. Rotation keeps
 * the newest complete entries only; this class deliberately never writes to
 * Android logging so storage failures cannot recurse through the log pipeline.
 * Production code gives the sole instance to [AppLogDispatcher].
 */
internal class AppLogStore private constructor(
    locations: Locations,
    private val maxLines: Int,
    private val maxBytes: Int,
    private val maxEntryBytes: Int,
) : AppLogStorage {
    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 1024 * 1024
        const val DEFAULT_MAX_ENTRY_BYTES = 64 * 1024
    }

    constructor(
        context: Context,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    ) : this(
        locations = Locations(context.filesDir, context.cacheDir),
        maxLines = maxLines,
        maxBytes = maxBytes,
        maxEntryBytes = maxEntryBytes,
    )

    internal constructor(
        filesDir: File,
        cacheDir: File,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    ) : this(
        locations = Locations(filesDir, cacheDir),
        maxLines = maxLines,
        maxBytes = maxBytes,
        maxEntryBytes = maxEntryBytes,
    )

    private val logDirectory = File(locations.filesDir, "logs")
    private val logFile = File(logDirectory, "videoslim.log")
    private val shareDirectory = File(locations.cacheDir, "shared")
    private val shareFile = File(shareDirectory, "videoslim-debug-log.txt")
    private var knownLineCount: Int? = null

    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxBytes >= 4) { "maxBytes must be at least four bytes" }
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
    }

    @Synchronized
    override fun append(entry: String) {
        val previousLineCount = knownLineCount ?: countLines()
        val sanitized =
            AppLogEntryNormalizer.normalize(
                entry,
                byteLimit = min(maxEntryBytes, maxBytes - 1),
            )
        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create log directory")
        }
        logFile.appendText("$sanitized\n", StandardCharsets.UTF_8)
        knownLineCount = previousLineCount + 1
        rotateIfNeeded()
    }

    @Synchronized
    override fun readAll(): String {
        if (!logFile.isFile || logFile.length() == 0L) {
            knownLineCount = 0
            return ""
        }
        knownLineCount = countLines()
        rotateIfNeeded()
        return if (logFile.isFile) {
            logFile.readText(StandardCharsets.UTF_8)
        } else {
            ""
        }
    }

    @Synchronized
    override fun createShareSnapshot(): File {
        val text = readAll()
        if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create shared log directory")
        }
        shareFile.writeText(text, StandardCharsets.UTF_8)
        return shareFile
    }

    private fun rotateIfNeeded() {
        val lineCount = knownLineCount ?: countLines()
        if (lineCount <= maxLines && logFile.length() <= maxBytes.toLong()) return

        val allLines = logFile.readLines(StandardCharsets.UTF_8)
        val newestReversed = ArrayList<String>(min(maxLines, allLines.size))
        var retainedBytes = 0
        for (index in allLines.lastIndex downTo 0) {
            if (newestReversed.size >= maxLines) break
            val line = allLines[index]
            val lineBytes = AppLogEntryNormalizer.utf8Size(line) + 1
            if (retainedBytes + lineBytes > maxBytes) break
            newestReversed.add(line)
            retainedBytes += lineBytes
        }

        newestReversed.reverse()
        val rotated = if (newestReversed.isEmpty()) {
            ""
        } else {
            newestReversed.joinToString(separator = "\n", postfix = "\n")
        }
        logFile.writeText(rotated, StandardCharsets.UTF_8)
        knownLineCount = newestReversed.size
    }

    private fun countLines(): Int {
        if (!logFile.isFile || logFile.length() == 0L) return 0
        return logFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.count() }
    }

    private data class Locations(
        val filesDir: File,
        val cacheDir: File,
    )
}
