package com.videoslim.videoslim

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CancellationException

internal fun isValidContentVideoUri(value: String): Boolean {
    val prefix = "content://"
    if (!value.startsWith(prefix)) return false
    val authority = value.substring(prefix.length).substringBefore('/')
    return authority.isNotBlank() &&
        authority.none(Char::isWhitespace) &&
        value.none { it.code < 0x20 || it.code == 0x7F }
}

enum class EngineErrorCode(
    val wireName: String,
    val defaultMessage: String,
) {
    INSUFFICIENT_STORAGE("INSUFFICIENT_STORAGE", "存储空间不足，请释放空间后重试"),
    ENCODER_UNAVAILABLE("ENCODER_UNAVAILABLE", "设备没有可用的 HEVC 硬件编码器"),
    SOURCE_CORRUPTED("SOURCE_CORRUPTED", "无法处理源视频，文件可能已损坏或格式不受支持"),
    CANCELLED("CANCELLED", "任务已取消"),
    UNKNOWN("UNKNOWN", "处理失败，请稍后重试"),
}

data class EngineFailure(
    val code: EngineErrorCode,
    val message: String = code.defaultMessage,
)

internal class EngineOperationException(
    val failure: EngineFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

internal class ProcessRequestException(
    val error: EngineFailure,
) : IllegalArgumentException(error.message)

internal data class ProcessRequest(
    val sourceUri: String,
    val outputFileName: String,
    val videoBitrate: Int,
) {
    companion object {
        private val rootKeys = setOf("uri", "outputFileName", "video", "audio")
        private val videoKeys =
            setOf("codec", "bitrate", "longEdge", "crop", "trimStartMs", "trimEndMs")
        private val audioKeys = setOf("mode", "bitrate")

        fun parse(arguments: Any?): ProcessRequest {
            val root = arguments.exactMap("压缩参数必须是完整对象", rootKeys)
            val sourceUri = root["uri"] as? String
                ?: invalid("uri 必须是 content:// 字符串")
            if (!isValidContentVideoUri(sourceUri)) {
                invalid("M1 仅支持系统选择器返回的 content:// 视频 URI")
            }

            val outputFileName = root["outputFileName"] as? String
                ?: invalid("outputFileName 必须是安全文件名")
            validateOutputName(outputFileName)

            val video = root["video"].exactMap("video 必须是完整对象", videoKeys)
            if (video["codec"] != HEVC_CODEC) {
                invalid("M1 仅支持 HEVC 压缩，不支持其他视频编码")
            }
            val videoBitrate = video["bitrate"].positiveChannelInt("video.bitrate")
            listOf("longEdge", "crop", "trimStartMs", "trimEndMs").forEach { key ->
                if (video[key] != null) {
                    invalid("M1 暂不支持 video.$key，请传 null")
                }
            }

            val audio = root["audio"].exactMap("audio 必须是完整对象", audioKeys)
            if (audio["mode"] != COPY_AUDIO_MODE) {
                invalid("M1 音频仅支持 copy 模式")
            }
            if (audio["bitrate"] != null) {
                invalid("M1 copy 音频模式的 bitrate 必须为 null")
            }

            return ProcessRequest(
                sourceUri = sourceUri,
                outputFileName = outputFileName,
                videoBitrate = videoBitrate,
            )
        }

        private fun validateOutputName(name: String) {
            val forbiddenCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
            val stem = name.substringBeforeLast('.', missingDelimiterValue = "")
            if (
                name.isBlank() ||
                name == "." ||
                name == ".." ||
                name.length > MAX_OUTPUT_NAME_LENGTH ||
                name.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_NAME_BYTES ||
                !name.endsWith(MP4_EXTENSION, ignoreCase = true) ||
                stem.isBlank() ||
                name.last().isWhitespace() ||
                name.endsWith('.') ||
                name.any { character ->
                    character in forbiddenCharacters ||
                        character.code < SPACE_CHARACTER_CODE ||
                        character.code == DELETE_CHARACTER_CODE
                }
            ) {
                invalid("outputFileName 必须是安全的非空 .mp4 文件名")
            }
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
            val value = when (this) {
                is Byte -> toInt()
                is Short -> toInt()
                is Int -> this
                is Long -> takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            }
            if (value == null || value <= 0) {
                invalid("$fieldName 必须是大于 0 的整数")
            }
            return value
        }

        private fun invalid(message: String): Nothing =
            throw ProcessRequestException(
                EngineFailure(
                    code = EngineErrorCode.UNKNOWN,
                    message = message,
                ),
            )

        private const val HEVC_CODEC = "hevc"
        private const val COPY_AUDIO_MODE = "copy"
        private const val MAX_OUTPUT_NAME_LENGTH = 255
        private const val MAX_OUTPUT_NAME_BYTES = 240
        private const val MP4_EXTENSION = ".mp4"
        private const val SPACE_CHARACTER_CODE = 0x20
        private const val DELETE_CHARACTER_CODE = 0x7F
    }
}

internal object EngineErrorMapper {
    private val decoderErrorCodes = setOf(3001, 3002, 3003)
    private val unavailableEncoderErrorCodes = setOf(4001, 4003)

    fun fromExportErrorCode(errorCode: Int): EngineFailure =
        when (errorCode) {
            in decoderErrorCodes -> EngineFailure(EngineErrorCode.SOURCE_CORRUPTED)
            in unavailableEncoderErrorCodes -> EngineFailure(EngineErrorCode.ENCODER_UNAVAILABLE)
            else ->
                EngineFailure(
                    code = EngineErrorCode.UNKNOWN,
                    message = "视频处理失败（Media3 错误码 $errorCode）",
                )
        }

    fun fromThrowable(error: Throwable): EngineFailure {
        val chain = error.causeChain()
        if (chain.any { it is CancellationException }) {
            return EngineFailure(EngineErrorCode.CANCELLED)
        }
        if (
            chain.any { throwable ->
                val normalized = throwable.message.orEmpty().lowercase(Locale.ROOT)
                normalized.contains("enospc") ||
                    normalized.contains("no space left") ||
                    normalized.contains("disk full")
            }
        ) {
            return EngineFailure(EngineErrorCode.INSUFFICIENT_STORAGE)
        }
        return EngineFailure(EngineErrorCode.UNKNOWN)
    }

    private fun Throwable.causeChain(): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = this
        while (current != null && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }
}
