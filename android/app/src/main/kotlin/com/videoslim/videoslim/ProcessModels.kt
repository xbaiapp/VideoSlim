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

internal enum class TaskKind(
    val wireName: String,
) {
    VIDEO_COMPRESSION("video_compression"),
    AUDIO_EXTRACTION("audio_extraction"),
    ;

    companion object {
        /** Strict parser for all new task contracts: a missing kind remains invalid. */
        fun fromWireName(value: Any?): TaskKind? = entries.firstOrNull { it.wireName == value }

        /**
         * Compatibility parser reserved for snapshots created before task kinds existed.
         *
         * Do not use this for new audio events or requests: those must select
         * [AUDIO_EXTRACTION] explicitly.
         */
        fun fromLegacyVideoWireName(value: Any?): TaskKind? =
            if (value == null) VIDEO_COMPRESSION else fromWireName(value)
    }
}

enum class EngineErrorCode(
    val wireName: String,
    val defaultMessage: String,
) {
    INSUFFICIENT_STORAGE("INSUFFICIENT_STORAGE", "存储空间不足，请释放空间后重试"),
    ENCODER_UNAVAILABLE("ENCODER_UNAVAILABLE", "当前手机没有可用的视频压缩方式"),
    SOURCE_CORRUPTED("SOURCE_CORRUPTED", "无法处理源视频，文件可能已损坏或格式不受支持"),
    SOURCE_PERMISSION_LOST("SOURCE_PERMISSION_LOST", "无法继续读取这个视频，请重新选择文件"),
    SOURCE_UNAVAILABLE("SOURCE_UNAVAILABLE", "所选视频已移动、删除或暂时不可用"),
    SOURCE_PROVIDER_FAILED("SOURCE_PROVIDER_FAILED", "手机无法持续读取这个视频，请重新选择或稍后重试"),
    INVALID_CROP("INVALID_CROP", "裁剪区域无效，请重新框选"),

    VIDEO_DECODING_FAILED("VIDEO_DECODING_FAILED", "手机的视频解码器未能完成此次处理，原视频没有被修改"),
    VIDEO_FORMAT_UNSUPPORTED("VIDEO_FORMAT_UNSUPPORTED", "这台手机暂时无法读取这种视频格式"),
    COMPATIBILITY_DECODER_UNAVAILABLE(
        "COMPATIBILITY_DECODER_UNAVAILABLE",
        "这台手机没有可用于此视频的软件读取方式，原视频没有被修改",
    ),
    VIDEO_ENCODING_FAILED("VIDEO_ENCODING_FAILED", "手机没能按当前设置完成压缩，可按原设置重试或调整格式和画质"),
    CAPTURE_METADATA_FAILED(
        "CAPTURE_METADATA_FAILED",
        "无法确认原拍摄时间或位置已保留，未保存不完整结果",
    ),
    AUDIO_TRACK_MISSING("AUDIO_TRACK_MISSING", "这个视频没有可提取的音轨"),
    AUDIO_COPY_UNSUPPORTED("AUDIO_COPY_UNSUPPORTED", "源音轨不是 AAC，请改用 AAC 转码"),
    AUDIO_CHANNEL_LAYOUT_UNSUPPORTED(
        "AUDIO_CHANNEL_LAYOUT_UNSUPPORTED",
        "暂不支持提取超过双声道的音频，请更换视频后重试",
    ),
    AUDIO_DECODING_FAILED("AUDIO_DECODING_FAILED", "手机无法读取源音频，原视频没有被修改"),
    AUDIO_ENCODING_FAILED("AUDIO_ENCODING_FAILED", "手机没能完成 AAC 音频编码，原视频没有被修改"),
    AUDIO_OUTPUT_INVALID("AUDIO_OUTPUT_INVALID", "提取的音频文件不完整，未保存公共输出"),
    OUTPUT_PERMISSION_LOST("OUTPUT_PERMISSION_LOST", "保存文件夹权限已失效，请重新选择保存位置"),
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

internal enum class VideoEncoderMode(
    val wireName: String,
) {
    UNKNOWN("unknown"),
    EXPLICIT_HARDWARE("explicit_hardware"),
    AMBIGUOUS_VENDOR("ambiguous_vendor"),
    SOFTWARE("software"),
}

internal enum class VideoCodec(
    val wireName: String,
) {
    HEVC("hevc"),
    H264("h264"),
    ;

    companion object {
        fun fromWireName(value: Any?): VideoCodec? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class VideoDecoderMode(val wireName: String) {
    HARDWARE("hardware"),
    SOFTWARE("software"),
    ;

    companion object {
        fun fromWireName(value: Any?): VideoDecoderMode? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class AudioMode(
    val wireName: String,
) {
    COPY("copy"),
    REENCODE("reencode"),
    REMOVE("remove"),
    ;

    companion object {
        fun fromWireName(value: Any?): AudioMode? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class PreviewFrameRequest(
    val sourceUri: String,
    val timeMs: Long,
) {
    companion object {
        fun parse(arguments: Any?): PreviewFrameRequest {
            val map = arguments as? Map<*, *>
                ?: throw IllegalArgumentException("预览帧参数必须是对象")
            val expectedKeys = setOf("uri", "timeMs")
            if (map.keys.any { it !is String } || map.keys != expectedKeys) {
                throw IllegalArgumentException("预览帧字段必须严格为 ${expectedKeys.sorted()}")
            }
            val sourceUri = map["uri"] as? String
                ?: throw IllegalArgumentException("uri 必须是字符串")
            if (!isValidContentVideoUri(sourceUri)) {
                throw IllegalArgumentException("uri 必须是有效的 content:// URI")
            }
            val timeMs =
                when (val value = map["timeMs"]) {
                    is Byte -> value.toLong()
                    is Short -> value.toLong()
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw IllegalArgumentException("timeMs 必须是整数")
                }
            if (timeMs < 0L) throw IllegalArgumentException("timeMs 不得小于 0")
            return PreviewFrameRequest(sourceUri = sourceUri, timeMs = timeMs)
        }
    }
}

internal data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    fun toChannelMap(): Map<String, Int> =
        linkedMapOf(
            "left" to left,
            "top" to top,
            "width" to width,
            "height" to height,
        )
}

internal data class ProcessRequest(
    val sourceUri: String,
    val outputFileName: String,
    val outputTreeUri: String? = null,
    val outputLocationLabel: String = "系统相册 > Movies > VideoSlim",
    val videoCodec: VideoCodec,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.HARDWARE,
    val videoBitrate: Int,
    val longEdge: Int?,
    val crop: CropRect? = null,
    val audioMode: AudioMode,
    val audioBitrate: Int?,
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
            "video" to
                linkedMapOf(
                    "codec" to videoCodec.wireName,
                    "decoderMode" to videoDecoderMode.wireName,
                    "bitrate" to videoBitrate,
                    "longEdge" to longEdge,
                    "crop" to crop?.toChannelMap(),
                    "trimStartMs" to null,
                    "trimEndMs" to null,
                ),
            "audio" to
                linkedMapOf(
                    "mode" to audioMode.wireName,
                    "bitrate" to audioBitrate,
                ),
        )

    companion object {
        private val rootKeys = setOf("uri", "outputFileName", "destination", "video", "audio")
        private val destinationKeys = setOf("treeUri", "label")
        private val videoKeys =
            setOf(
                "codec",
                "decoderMode",
                "bitrate",
                "longEdge",
                "crop",
                "trimStartMs",
                "trimEndMs",
            )
        private val audioKeys = setOf("mode", "bitrate")
        private val allowedLongEdges = setOf(1_920, 1_280, 854)
        private val allowedAudioBitrates = setOf(192_000, 128_000, 96_000, 64_000)

        fun parse(arguments: Any?): ProcessRequest {
            val root = arguments.exactMap("压缩参数必须是完整对象", rootKeys)
            val sourceUri = root["uri"] as? String
                ?: invalid("uri 必须是 content:// 字符串")
            if (!isValidContentVideoUri(sourceUri)) {
                invalid("M2 仅支持系统选择器返回的 content:// 视频 URI")
            }

            val outputFileName = root["outputFileName"] as? String
                ?: invalid("outputFileName 必须是安全文件名")
            validateOutputName(outputFileName)

            val destination =
                root["destination"].exactMap("destination 必须是完整对象", destinationKeys)
            val outputTreeUri = destination["treeUri"] as? String
            if (outputTreeUri != null && !isValidContentVideoUri(outputTreeUri)) {
                invalid("destination.treeUri 必须是系统文件夹 content:// URI 或 null")
            }
            val outputLocationLabel = destination["label"] as? String
                ?: invalid("destination.label 必须是保存位置说明")
            if (
                outputLocationLabel.isBlank() ||
                outputLocationLabel.length > MAX_OUTPUT_LOCATION_LABEL_LENGTH ||
                outputLocationLabel.any { it.code < SPACE_CHARACTER_CODE || it.code == DELETE_CHARACTER_CODE }
            ) {
                invalid("destination.label 格式无效")
            }

            val video = root["video"].exactMap("video 必须是完整对象", videoKeys)
            val videoCodec =
                VideoCodec.fromWireName(video["codec"])
                    ?: invalid("video.codec 必须严格为 hevc 或 h264")
            val videoDecoderMode =
                VideoDecoderMode.fromWireName(video["decoderMode"])
                    ?: invalid("video.decoderMode 必须严格为 hardware 或 software")
            val videoBitrate = video["bitrate"].positiveChannelInt("video.bitrate")
            val longEdge =
                video["longEdge"]?.positiveChannelInt("video.longEdge")?.also { value ->
                    if (value !in allowedLongEdges) {
                        invalid("video.longEdge 必须为 null、1920、1280 或 854")
                    }
                }
            val crop = parseCrop(video["crop"])
            listOf("trimStartMs", "trimEndMs").forEach { key ->
                if (video[key] != null) {
                    invalid("M4-A 暂不支持 video.$key，请传 null")
                }
            }

            val audio = root["audio"].exactMap("audio 必须是完整对象", audioKeys)
            val audioMode =
                AudioMode.fromWireName(audio["mode"])
                    ?: invalid("audio.mode 必须严格为 copy、reencode 或 remove")
            val audioBitrate = parseAudioBitrate(audioMode, audio["bitrate"])

            return ProcessRequest(
                sourceUri = sourceUri,
                outputFileName = outputFileName,
                outputTreeUri = outputTreeUri,
                outputLocationLabel = outputLocationLabel,
                videoCodec = videoCodec,
                videoDecoderMode = videoDecoderMode,
                videoBitrate = videoBitrate,
                longEdge = longEdge,
                crop = crop,
                audioMode = audioMode,
                audioBitrate = audioBitrate,
            )
        }

        private fun parseCrop(value: Any?): CropRect? {
            if (value == null) return null
            return try {
                val crop =
                    value.exactMap(
                        "video.crop 必须是完整对象",
                        setOf("left", "top", "width", "height"),
                    )
                CropRect(
                    left = crop["left"].nonNegativeChannelInt("video.crop.left"),
                    top = crop["top"].nonNegativeChannelInt("video.crop.top"),
                    width = crop["width"].positiveChannelInt("video.crop.width"),
                    height = crop["height"].positiveChannelInt("video.crop.height"),
                )
            } catch (error: ProcessRequestException) {
                throw ProcessRequestException(
                    EngineFailure(EngineErrorCode.INVALID_CROP, "裁剪区域无效，请重新框选"),
                )
            }
        }

        private fun parseAudioBitrate(
            mode: AudioMode,
            value: Any?,
        ): Int? =
            when (mode) {
                AudioMode.COPY -> {
                    if (value != null) invalid("copy 音频模式的 bitrate 必须为 null")
                    null
                }
                AudioMode.REENCODE -> {
                    val bitrate = value.positiveChannelInt("audio.bitrate")
                    if (bitrate !in allowedAudioBitrates) {
                        invalid("reencode 音频 bitrate 必须为 192000、128000、96000 或 64000")
                    }
                    bitrate
                }
                AudioMode.REMOVE -> {
                    if (value != null) invalid("remove 音频模式的 bitrate 必须为 null")
                    null
                }
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

        private fun Any?.nonNegativeChannelInt(fieldName: String): Int {
            val value =
                when (this) {
                    is Byte -> toInt()
                    is Short -> toInt()
                    is Int -> this
                    is Long -> takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                    else -> null
                }
            if (value == null || value < 0) {
                invalid("$fieldName 必须是大于等于 0 的整数")
            }
            return value
        }

        private fun Any?.positiveChannelInt(fieldName: String): Int {
            val value =
                when (this) {
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

        private const val MAX_OUTPUT_NAME_LENGTH = 255
        private const val MAX_OUTPUT_LOCATION_LABEL_LENGTH = 512
        private const val MAX_OUTPUT_NAME_BYTES = 240
        private const val MP4_EXTENSION = ".mp4"
        private const val SPACE_CHARACTER_CODE = 0x20
        private const val DELETE_CHARACTER_CODE = 0x7F
    }
}

internal object EngineErrorMapper {
    private val decoderRuntimeErrorCodes = setOf(3001, 3002)
    private val unavailableEncoderErrorCodes = setOf(4001, 4003)

    fun fromExportErrorCode(
        errorCode: Int,
        wasHdrToneMapping: Boolean = false,
    ): EngineFailure {
        val baseFailure =
            when (errorCode) {
                in decoderRuntimeErrorCodes -> EngineFailure(EngineErrorCode.VIDEO_DECODING_FAILED)
                3003 -> EngineFailure(EngineErrorCode.VIDEO_FORMAT_UNSUPPORTED)
                in unavailableEncoderErrorCodes -> EngineFailure(EngineErrorCode.ENCODER_UNAVAILABLE)
                4002 -> EngineFailure(EngineErrorCode.VIDEO_ENCODING_FAILED)
                else ->
                    EngineFailure(
                        code = EngineErrorCode.UNKNOWN,
                        message = EngineErrorCode.UNKNOWN.defaultMessage,
                    )
            }
        if (
            !wasHdrToneMapping ||
            baseFailure.code == EngineErrorCode.VIDEO_DECODING_FAILED ||
            baseFailure.code == EngineErrorCode.VIDEO_FORMAT_UNSUPPORTED
        ) {
            return baseFailure
        }
        return EngineFailure(
            code = baseFailure.code,
            message = "手机无法完成这个 HDR 视频的画面转换",
        )
    }

    fun fromThrowable(error: Throwable): EngineFailure {
        val chain = error.causeChain()
        if (chain.any { it is OutputPermissionException }) {
            return EngineFailure(EngineErrorCode.OUTPUT_PERMISSION_LOST)
        }
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
