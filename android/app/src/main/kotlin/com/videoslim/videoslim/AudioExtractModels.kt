package com.videoslim.videoslim

internal enum class AudioExtractMode(
    val wireName: String,
) {
    COPY("copy"),
    AAC("aac"),
    ;

    companion object {
        fun fromWireName(value: Any?): AudioExtractMode? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class AudioExtractRequest(
    val sourceUri: String,
    val outputFileName: String,
    val outputTreeUri: String?,
    val outputLocationLabel: String,
    val mode: AudioExtractMode,
    val bitrate: Int?,
) {
    fun toChannelMap(): Map<String, Any?> =
        linkedMapOf(
            "uri" to sourceUri,
            "outputFileName" to outputFileName,
            "destination" to
                linkedMapOf(
                    "treeUri" to outputTreeUri,
                    "label" to outputLocationLabel,
                ),
            "audio" to
                linkedMapOf(
                    "mode" to mode.wireName,
                    "bitrate" to bitrate,
                ),
        )

    companion object {
        private val rootKeys = setOf("uri", "outputFileName", "destination", "audio")
        private val destinationKeys = setOf("treeUri", "label")
        private val audioKeys = setOf("mode", "bitrate")
        private val allowedAacBitrates = setOf(192_000, 128_000, 96_000, 64_000)
        private val forbiddenOutputNameCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun parse(arguments: Any?): AudioExtractRequest {
            val root = arguments.exactMap("音频提取参数必须是完整对象", rootKeys)
            val sourceUri =
                root["uri"] as? String
                    ?: invalid("uri 必须是 content:// 字符串")
            if (!isValidContentUri(sourceUri)) {
                invalid("音频提取仅支持系统选择器返回的 content:// 视频 URI")
            }

            val outputFileName =
                root["outputFileName"] as? String
                    ?: invalid("outputFileName 必须是安全文件名")
            validateOutputName(outputFileName)

            val destination =
                root["destination"].exactMap("destination 必须是完整对象", destinationKeys)
            val outputTreeUri = parseOutputTreeUri(destination["treeUri"])
            val outputLocationLabel =
                destination["label"] as? String
                    ?: invalid("destination.label 必须是保存位置说明")
            validateOutputLocationLabel(outputLocationLabel)

            val audio = root["audio"].exactMap("audio 必须是完整对象", audioKeys)
            val mode =
                AudioExtractMode.fromWireName(audio["mode"])
                    ?: invalid("audio.mode 必须严格为 copy 或 aac")
            val bitrate = parseBitrate(mode, audio["bitrate"])

            return AudioExtractRequest(
                sourceUri = sourceUri,
                outputFileName = outputFileName,
                outputTreeUri = outputTreeUri,
                outputLocationLabel = outputLocationLabel,
                mode = mode,
                bitrate = bitrate,
            )
        }

        private fun parseOutputTreeUri(value: Any?): String? =
            when (value) {
                null -> null
                is String -> {
                    if (!isValidContentUri(value)) {
                        invalid("destination.treeUri 必须是系统文件夹 content:// URI 或 null")
                    }
                    value
                }
                else -> invalid("destination.treeUri 必须是系统文件夹 content:// URI 或 null")
            }

        private fun parseBitrate(
            mode: AudioExtractMode,
            value: Any?,
        ): Int? =
            when (mode) {
                AudioExtractMode.COPY -> {
                    if (value != null) invalid("copy 音频提取模式的 bitrate 必须为 null")
                    null
                }
                AudioExtractMode.AAC -> {
                    val bitrate = value.positiveChannelInt("audio.bitrate")
                    if (bitrate !in allowedAacBitrates) {
                        invalid("aac 音频 bitrate 必须为 192000、128000、96000 或 64000")
                    }
                    bitrate
                }
            }

        private fun validateOutputName(name: String) {
            val stem = name.substringBeforeLast('.', missingDelimiterValue = "")
            if (
                name.isBlank() ||
                name == "." ||
                name == ".." ||
                name.length > MAX_OUTPUT_NAME_LENGTH ||
                name.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_NAME_BYTES ||
                !name.endsWith(M4A_EXTENSION, ignoreCase = true) ||
                stem.isBlank() ||
                name.last().isWhitespace() ||
                name.endsWith('.') ||
                name.any { character ->
                    character in forbiddenOutputNameCharacters || isControlCharacter(character)
                }
            ) {
                invalid("outputFileName 必须是安全的非空 .m4a 文件名")
            }
        }

        private fun validateOutputLocationLabel(label: String) {
            if (
                label.isBlank() ||
                label.length > MAX_OUTPUT_LOCATION_LABEL_LENGTH ||
                label.any(::isControlCharacter)
            ) {
                invalid("destination.label 格式无效")
            }
        }

        private fun isValidContentUri(value: String): Boolean {
            if (
                value.isBlank() ||
                value.length > MAX_CONTENT_URI_LENGTH ||
                value.any(::isControlCharacter) ||
                !value.startsWith(CONTENT_URI_PREFIX)
            ) {
                return false
            }
            val authority = value.substring(CONTENT_URI_PREFIX.length).substringBefore('/')
            return authority.isNotBlank() && authority.none(Char::isWhitespace)
        }

        private fun Any?.exactMap(
            description: String,
            expectedKeys: Set<String>,
        ): Map<*, *> {
            val value = this as? Map<*, *> ?: invalid(description)
            if (value.keys.any { it !is String } || value.keys != expectedKeys) {
                invalid("$description，字段必须严格为 ${expectedKeys.sorted().joinToString()}")
            }
            return value
        }

        private fun Any?.positiveChannelInt(fieldName: String): Int {
            val parsed =
                when (this) {
                    is Byte -> toInt()
                    is Short -> toInt()
                    is Int -> this
                    is Long -> takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                    else -> null
                }
            if (parsed == null || parsed <= 0) {
                invalid("$fieldName 必须是大于 0 的整数")
            }
            return parsed
        }

        private fun isControlCharacter(character: Char): Boolean =
            character.code < SPACE_CHARACTER_CODE || character.code == DELETE_CHARACTER_CODE

        private fun invalid(message: String): Nothing =
            throw ProcessRequestException(
                EngineFailure(
                    code = EngineErrorCode.UNKNOWN,
                    message = message,
                ),
            )

        private const val MAX_CONTENT_URI_LENGTH = 4_096
        private const val MAX_OUTPUT_NAME_LENGTH = 255
        private const val MAX_OUTPUT_NAME_BYTES = 240
        private const val MAX_OUTPUT_LOCATION_LABEL_LENGTH = 512
        private const val CONTENT_URI_PREFIX = "content://"
        private const val M4A_EXTENSION = ".m4a"
        private const val SPACE_CHARACTER_CODE = 0x20
        private const val DELETE_CHARACTER_CODE = 0x7F
    }
}
