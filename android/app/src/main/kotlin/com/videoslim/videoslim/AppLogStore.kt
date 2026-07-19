package com.videoslim.videoslim

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Thread-safe append-only log storage with count and UTF-8 byte rotation.
 *
 * The active file is always kept at filesDir/logs/videoslim.log. Rotation keeps
 * the newest complete entries only; this class deliberately never writes to
 * Android logging so storage failures cannot recurse through the log pipeline.
 */
class AppLogStore(
    context: Context,
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 1024 * 1024
        const val DEFAULT_MAX_ENTRY_BYTES = 64 * 1024
    }

    private val logDirectory = File(context.filesDir, "logs")
    private val logFile = File(logDirectory, "videoslim.log")
    private val shareDirectory = File(context.cacheDir, "shared")
    private val shareFile = File(shareDirectory, "videoslim-debug-log.txt")
    private var knownLineCount: Int? = null

    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxBytes >= 4) { "maxBytes must be at least four bytes" }
        require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
    }

    @Synchronized
    fun append(entry: String) {
        val previousLineCount = knownLineCount ?: countLines()
        val sanitized = sanitizeEntry(entry)
        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create log directory")
        }
        logFile.appendText("$sanitized\n", StandardCharsets.UTF_8)
        knownLineCount = previousLineCount + 1
        rotateIfNeeded()
    }

    @Synchronized
    fun readAll(): String {
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
    fun createShareSnapshot(): File {
        val text = readAll()
        if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create shared log directory")
        }
        shareFile.writeText(text, StandardCharsets.UTF_8)
        return shareFile
    }

    private fun sanitizeEntry(entry: String): String {
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
        val boundedBytes = min(maxEntryBytes, maxBytes - 1)
        return truncateUtf8(singleLine, boundedBytes)
    }

    private fun rotateIfNeeded() {
        val lineCount = knownLineCount ?: countLines()
        if (lineCount <= maxLines && logFile.length() <= maxBytes.toLong()) {
            return
        }

        val allLines = logFile.readLines(StandardCharsets.UTF_8)
        val newestReversed = ArrayList<String>(min(maxLines, allLines.size))
        var retainedBytes = 0
        for (index in allLines.lastIndex downTo 0) {
            if (newestReversed.size >= maxLines) {
                break
            }
            val line = allLines[index]
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1
            if (retainedBytes + lineBytes > maxBytes) {
                break
            }
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
        if (!logFile.isFile || logFile.length() == 0L) {
            return 0
        }
        return logFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.count()
        }
    }

    private fun truncateUtf8(value: String, limit: Int): String {
        if (value.toByteArray(StandardCharsets.UTF_8).size <= limit) {
            return value
        }

        val suffix = "..."
        val includeSuffix = limit >= suffix.toByteArray(StandardCharsets.UTF_8).size
        val contentLimit = if (includeSuffix) limit - 3 else limit
        val result = StringBuilder()
        var retainedBytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val character = String(Character.toChars(codePoint))
            val characterBytes = character.toByteArray(StandardCharsets.UTF_8).size
            if (retainedBytes + characterBytes > contentLimit) {
                break
            }
            result.append(character)
            retainedBytes += characterBytes
            offset += Character.charCount(codePoint)
        }
        if (includeSuffix) {
            result.append(suffix)
        }
        return result.toString()
    }
}
