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

/** Observable facts from the pure prefix-bounded normalizer. */
internal data class AppLogNormalizationResult(
    val value: String,
    val utf8ByteCount: Int,
    val inspectedCodeUnits: Int,
    val truncated: Boolean,
)

/** Pure one-line normalization shared by queue admission and file storage. */
internal object AppLogEntryNormalizer {
    const val TRUNCATION_MARKER = "..."
    private const val REPLACEMENT_CHARACTER = '\uFFFD'
    private const val REPLACEMENT_CHARACTER_UTF8_BYTES = 3
    private const val MAX_RECENT_CHUNKS = 3
    private const val INITIAL_CAPACITY_LIMIT = 64 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"

    fun normalize(
        entry: String,
        byteLimit: Int,
    ): String = normalizePrefixBounded(entry, byteLimit).value

    /**
     * Escapes and truncates while inspecting only the source prefix that can
     * contribute to the bounded result. No allocation scales with [entry.length].
     */
    fun normalizePrefixBounded(
        entry: String,
        byteLimit: Int,
    ): AppLogNormalizationResult {
        require(byteLimit > 0) { "byteLimit must be positive" }
        val result =
            StringBuilder(
                minOf(entry.length, byteLimit, INITIAL_CAPACITY_LIMIT),
            )
        val recentStarts = IntArray(MAX_RECENT_CHUNKS)
        val recentByteCounts = IntArray(MAX_RECENT_CHUNKS)
        var recentSize = 0
        var utf8ByteCount = 0
        var inspectedCodeUnits = 0
        var offset = 0

        while (offset < entry.length) {
            val first = entry[offset]
            val sourceCodeUnits: Int
            val chunkUtf8Bytes: Int
            val kind: ChunkKind
            when {
                first == '\r' -> {
                    sourceCodeUnits = 1
                    chunkUtf8Bytes = 2
                    kind = ChunkKind.ESCAPED_CARRIAGE_RETURN
                }
                first == '\n' -> {
                    sourceCodeUnits = 1
                    chunkUtf8Bytes = 2
                    kind = ChunkKind.ESCAPED_NEWLINE
                }
                first == '\t' -> {
                    sourceCodeUnits = 1
                    chunkUtf8Bytes = 2
                    kind = ChunkKind.ESCAPED_TAB
                }
                first.code < 0x20 -> {
                    sourceCodeUnits = 1
                    chunkUtf8Bytes = 6
                    kind = ChunkKind.ESCAPED_CONTROL
                }
                Character.isHighSurrogate(first) &&
                    offset + 1 < entry.length &&
                    Character.isLowSurrogate(entry[offset + 1]) -> {
                    sourceCodeUnits = 2
                    chunkUtf8Bytes = 4
                    kind = ChunkKind.SURROGATE_PAIR
                }
                Character.isSurrogate(first) -> {
                    sourceCodeUnits = 1
                    chunkUtf8Bytes = REPLACEMENT_CHARACTER_UTF8_BYTES
                    kind = ChunkKind.REPLACEMENT
                }
                else -> {
                    sourceCodeUnits = 1
                    chunkUtf8Bytes =
                        when {
                            first.code <= 0x7f -> 1
                            first.code <= 0x7ff -> 2
                            else -> 3
                        }
                    kind = ChunkKind.SINGLE_CHARACTER
                }
            }
            inspectedCodeUnits += sourceCodeUnits
            if (chunkUtf8Bytes > byteLimit - utf8ByteCount) break

            val chunkStart = result.length
            when (kind) {
                ChunkKind.ESCAPED_CARRIAGE_RETURN -> result.append("\\r")
                ChunkKind.ESCAPED_NEWLINE -> result.append("\\n")
                ChunkKind.ESCAPED_TAB -> result.append("\\t")
                ChunkKind.ESCAPED_CONTROL -> appendControlEscape(result, first.code)
                ChunkKind.SURROGATE_PAIR -> result.append(first).append(entry[offset + 1])
                ChunkKind.REPLACEMENT -> result.append(REPLACEMENT_CHARACTER)
                ChunkKind.SINGLE_CHARACTER -> result.append(first)
            }
            if (recentSize == MAX_RECENT_CHUNKS) {
                for (index in 1 until MAX_RECENT_CHUNKS) {
                    recentStarts[index - 1] = recentStarts[index]
                    recentByteCounts[index - 1] = recentByteCounts[index]
                }
                recentSize -= 1
            }
            recentStarts[recentSize] = chunkStart
            recentByteCounts[recentSize] = chunkUtf8Bytes
            recentSize += 1
            utf8ByteCount += chunkUtf8Bytes
            offset += sourceCodeUnits
        }

        val truncated = offset < entry.length
        if (truncated) {
            val marker = if (byteLimit >= TRUNCATION_MARKER.length) {
                TRUNCATION_MARKER
            } else {
                ".".repeat(byteLimit)
            }
            val markerBytes = marker.length
            while (utf8ByteCount > byteLimit - markerBytes) {
                check(recentSize > 0) { "normalizer lost a retained chunk boundary" }
                recentSize -= 1
                result.setLength(recentStarts[recentSize])
                utf8ByteCount -= recentByteCounts[recentSize]
            }
            result.append(marker)
            utf8ByteCount += markerBytes
        }

        return AppLogNormalizationResult(
            value = result.toString(),
            utf8ByteCount = utf8ByteCount,
            inspectedCodeUnits = inspectedCodeUnits,
            truncated = truncated,
        )
    }

    /** Counts UTF-8 bytes without allocating a whole encoded copy. */
    fun utf8Size(value: String): Int {
        var total = 0
        var offset = 0
        while (offset < value.length) {
            val first = value[offset]
            val sourceCodeUnits: Int
            val bytes: Int
            if (
                Character.isHighSurrogate(first) &&
                offset + 1 < value.length &&
                Character.isLowSurrogate(value[offset + 1])
            ) {
                sourceCodeUnits = 2
                bytes = 4
            } else {
                sourceCodeUnits = 1
                bytes =
                    when {
                        Character.isSurrogate(first) -> REPLACEMENT_CHARACTER_UTF8_BYTES
                        first.code <= 0x7f -> 1
                        first.code <= 0x7ff -> 2
                        else -> 3
                    }
            }
            if (total > Int.MAX_VALUE - bytes) return Int.MAX_VALUE
            total += bytes
            offset += sourceCodeUnits
        }
        return total
    }

    private fun appendControlEscape(
        target: StringBuilder,
        value: Int,
    ) {
        target.append("\\u")
        target.append(HEX_DIGITS[(value ushr 12) and 0xf])
        target.append(HEX_DIGITS[(value ushr 8) and 0xf])
        target.append(HEX_DIGITS[(value ushr 4) and 0xf])
        target.append(HEX_DIGITS[value and 0xf])
    }

    private enum class ChunkKind {
        ESCAPED_CARRIAGE_RETURN,
        ESCAPED_NEWLINE,
        ESCAPED_TAB,
        ESCAPED_CONTROL,
        SURROGATE_PAIR,
        REPLACEMENT,
        SINGLE_CHARACTER,
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
