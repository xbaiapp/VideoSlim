package com.videoslim.videoslim

enum class OutputMediaKind(
    val extension: String,
    val mimeType: String,
    val scopedRelativePath: String,
    val publicDirectory: String,
    val defaultLocationLabel: String,
) {
    VIDEO_MP4(
        extension = ".mp4",
        mimeType = "video/mp4",
        scopedRelativePath = "Movies/VideoSlim/",
        publicDirectory = "Movies",
        defaultLocationLabel = "系统相册 > Movies > VideoSlim",
    ),
    AUDIO_M4A(
        extension = ".m4a",
        mimeType = "audio/mp4",
        scopedRelativePath = "Music/VideoSlim/",
        publicDirectory = "Music",
        defaultLocationLabel = "系统音频 > Music > VideoSlim",
    ),
    ;

    fun isSafeDisplayName(name: String): Boolean =
        isSafeDisplayName(name, MAX_OUTPUT_NAME_BYTES)

    fun isSafeLegacyV1DisplayName(name: String): Boolean =
        this == VIDEO_MP4 && isSafeDisplayName(name, LEGACY_V1_MAX_OUTPUT_NAME_BYTES)

    private fun isSafeDisplayName(name: String, maxUtf8Bytes: Int): Boolean =
        name.isNotBlank() &&
            name.length <= MAX_OUTPUT_NAME_LENGTH &&
            name.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes &&
            name.endsWith(extension, ignoreCase = true) &&
            name.substringBeforeLast('.', missingDelimiterValue = "").isNotBlank() &&
            name.lastOrNull()?.let { !it.isWhitespace() && it != '.' } == true &&
            '/' !in name &&
            '\\' !in name &&
            name.none { it.code < 0x20 || it.code == 0x7f }

    companion object {
        private const val MAX_OUTPUT_NAME_LENGTH = 255
        private const val MAX_OUTPUT_NAME_BYTES = 240
        private const val LEGACY_V1_MAX_OUTPUT_NAME_BYTES = 255
    }
}

internal fun isMediaStoreUriForKind(
    mediaKind: OutputMediaKind,
    value: String,
): Boolean =
    when (mediaKind) {
        OutputMediaKind.VIDEO_MP4 -> APP_MEDIA_VIDEO_URI.matches(value)
        OutputMediaKind.AUDIO_M4A -> APP_MEDIA_AUDIO_URI.matches(value)
    }

private val APP_MEDIA_VIDEO_URI =
    Regex("^content://media/external/video/media/[0-9]+$")
private val APP_MEDIA_AUDIO_URI =
    Regex("^content://media/external/audio/media/[0-9]+$")
