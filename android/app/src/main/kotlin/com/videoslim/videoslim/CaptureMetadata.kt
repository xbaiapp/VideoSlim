package com.videoslim.videoslim

import android.media.MediaMetadataRetriever
import androidx.media3.common.Metadata
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.InAppMp4Muxer
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.abs

internal data class CaptureLocation(
    val latitude: Double,
    val longitude: Double,
) {
    fun isValid(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in MIN_LATITUDE..MAX_LATITUDE &&
            longitude in MIN_LONGITUDE..MAX_LONGITUDE

    private companion object {
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}

internal data class SourceCaptureMetadata(
    val captureTimeEpochMs: Long?,
    val location: CaptureLocation?,
) {
    val hasCaptureTime: Boolean
        get() = captureTimeEpochMs != null

    val hasLocation: Boolean
        get() = location != null

    val isEmpty: Boolean
        get() = !hasCaptureTime && !hasLocation

    override fun toString(): String =
        "SourceCaptureMetadata(" +
            "captureTimePresent=$hasCaptureTime, " +
            "locationPresent=$hasLocation)"

    companion object {
        val EMPTY = SourceCaptureMetadata(captureTimeEpochMs = null, location = null)
    }
}

internal object CaptureMetadataParser {
    private val compactOffsetFormatters =
        listOf(
            compactOffsetFormatter("+HH:MM"),
            compactOffsetFormatter("+HHMM"),
            compactOffsetFormatter("+HH"),
        )
    private val locationPattern =
        Regex("^([+-]\\d{2}(?:\\.\\d+)?)([+-]\\d{3}(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?/?$")

    fun parseCaptureTime(value: String?): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed =
            sequenceOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .plus(compactOffsetFormatters.asSequence())
                .mapNotNull { formatter ->
                    runCatching {
                        OffsetDateTime.parse(normalized, formatter).toInstant().toEpochMilli()
                    }.getOrNull()
                }.firstOrNull()
        return parsed?.takeIf(::isWritableCaptureTime)
    }

    fun parseLocation(value: String?): CaptureLocation? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = locationPattern.matchEntire(normalized) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        return CaptureLocation(latitude, longitude).takeIf(CaptureLocation::isValid)
    }

    fun chooseCaptureTime(
        retrieverDate: String?,
        mediaStoreDateTakenEpochMs: Long?,
    ): Long? =
        parseCaptureTime(retrieverDate)
            ?: mediaStoreDateTakenEpochMs?.takeIf(::isWritableCaptureTime)

    fun isWritableCaptureTime(epochMs: Long): Boolean {
        if (epochMs <= 0L) return false
        val mp4Seconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(epochMs)
        return mp4Seconds in (MP4_UNIX_EPOCH_DELTA_SECONDS + 1)..UNSIGNED_INT_MAX
    }

    fun unixTimeFromMp4Seconds(mp4Seconds: Long): Long? {
        if (mp4Seconds !in (MP4_UNIX_EPOCH_DELTA_SECONDS + 1)..UNSIGNED_INT_MAX) return null
        val unixSeconds = mp4Seconds - MP4_UNIX_EPOCH_DELTA_SECONDS
        return runCatching { Math.multiplyExact(unixSeconds, MILLIS_PER_SECOND) }.getOrNull()
    }

    private fun compactOffsetFormatter(offsetPattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("uuuuMMdd'T'HHmmss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .appendOffset(offsetPattern, "Z")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)

    private const val MP4_UNIX_EPOCH_DELTA_SECONDS = 2_082_844_800L
    private const val UNSIGNED_INT_MAX = 4_294_967_295L
    private const val MILLIS_PER_SECOND = 1_000L
}

internal class CaptureMetadataPolicy(
    initialMetadata: SourceCaptureMetadata,
) : InAppMp4Muxer.MetadataProvider {
    private val initialMetadata = sanitize(initialMetadata)

    @Volatile
    private var resolved = this.initialMetadata

    override fun updateMetadataEntries(metadataEntries: MutableSet<Metadata.Entry>) {
        val sourceTimestamp =
            metadataEntries
                .filterIsInstance<Mp4TimestampData>()
                .firstNotNullOfOrNull { entry ->
                    CaptureMetadataParser.unixTimeFromMp4Seconds(entry.creationTimestampSeconds)
                }
        val sourceLocation =
            metadataEntries
                .filterIsInstance<Mp4LocationData>()
                .map { entry ->
                    CaptureLocation(
                        latitude = entry.latitude.toDouble(),
                        longitude = entry.longitude.toDouble(),
                    )
                }.firstOrNull(CaptureLocation::isValid)

        val selected =
            sanitize(
                SourceCaptureMetadata(
                    captureTimeEpochMs = initialMetadata.captureTimeEpochMs ?: sourceTimestamp,
                    location = initialMetadata.location ?: sourceLocation,
                ),
            )
        resolved = selected

        metadataEntries.clear()
        selected.captureTimeEpochMs?.let { epochMs ->
            val mp4Seconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(epochMs)
            metadataEntries += Mp4TimestampData(mp4Seconds, mp4Seconds)
        }
        selected.location?.let { location ->
            metadataEntries +=
                Mp4LocationData(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                )
        }
    }

    fun resolvedMetadata(): SourceCaptureMetadata = resolved

    private companion object {
        fun sanitize(metadata: SourceCaptureMetadata): SourceCaptureMetadata =
            SourceCaptureMetadata(
                captureTimeEpochMs =
                    metadata.captureTimeEpochMs?.takeIf(CaptureMetadataParser::isWritableCaptureTime),
                location = metadata.location?.takeIf(CaptureLocation::isValid),
            )
    }
}

internal object CaptureMetadataVerification {
    const val LOCATION_TOLERANCE_DEGREES = 0.0001

    fun mismatchFields(
        expected: SourceCaptureMetadata,
        actual: SourceCaptureMetadata,
    ): Set<String> =
        buildSet {
            expected.captureTimeEpochMs?.let { expectedTime ->
                val actualTime = actual.captureTimeEpochMs
                if (actualTime == null || expectedTime / 1_000L != actualTime / 1_000L) {
                    add("captureTime")
                }
            }
            expected.location?.let { expectedLocation ->
                val actualLocation = actual.location
                if (
                    actualLocation == null ||
                    abs(expectedLocation.latitude - actualLocation.latitude) >
                        LOCATION_TOLERANCE_DEGREES ||
                    abs(expectedLocation.longitude - actualLocation.longitude) >
                        LOCATION_TOLERANCE_DEGREES
                ) {
                    add("location")
                }
            }
        }
}

internal class CaptureMetadataVerificationException(
    val mismatchFields: Set<String>,
    cause: Throwable? = null,
) : Exception(
        "Capture metadata verification failed fields=" +
            mismatchFields.sorted().joinToString(separator = ","),
        cause,
    )

internal class CaptureMetadataFileVerifier(
    private val reader: (File) -> SourceCaptureMetadata = ::readCaptureMetadataFromFile,
) {
    fun verify(
        outputFile: File,
        expected: SourceCaptureMetadata,
    ) {
        if (expected.isEmpty) return
        val expectedFields = expected.fieldNames()
        val actual =
            try {
                reader(outputFile)
            } catch (error: Throwable) {
                throw CaptureMetadataVerificationException(expectedFields, error)
            }
        val mismatches = CaptureMetadataVerification.mismatchFields(expected, actual)
        if (mismatches.isNotEmpty()) {
            throw CaptureMetadataVerificationException(mismatches)
        }
    }
}

private fun SourceCaptureMetadata.fieldNames(): Set<String> =
    buildSet {
        if (hasCaptureTime) add("captureTime")
        if (hasLocation) add("location")
    }

private fun readCaptureMetadataFromFile(file: File): SourceCaptureMetadata {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        SourceCaptureMetadata(
            captureTimeEpochMs =
                CaptureMetadataParser.parseCaptureTime(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                ),
            location =
                CaptureMetadataParser.parseLocation(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                ),
        )
    } finally {
        runCatching { retriever.release() }
    }
}
