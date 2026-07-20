package com.videoslim.videoslim

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.Codec
import androidx.media3.transformer.EncoderSelector
import com.google.common.collect.ImmutableList
import java.util.Locale

internal data class CodecCandidate(
    val name: String,
    val isEncoder: Boolean,
    val isHardwareAccelerated: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean,
    val supportedTypes: Set<String>,
    val canonicalName: String = name,
    val isAlias: Boolean = false,
)

internal object HardwareCodecPolicy {
    fun select(
        candidates: List<CodecCandidate>,
        mimeType: String,
        encoder: Boolean,
    ): List<CodecCandidate> =
        candidates
            .asSequence()
            .filter { it.isEncoder == encoder }
            .filter { candidate ->
                candidate.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }.filter(::isHardwareEligible)
            .sortedWith(compareByDescending<CodecCandidate> { it.isVendor }.thenBy { it.name })
            .toList()

    fun isHardwareEligible(candidate: CodecCandidate): Boolean =
        isHardwareEligible(
            codecName = candidate.name,
            isHardwareAccelerated = candidate.isHardwareAccelerated,
            isSoftwareOnly = candidate.isSoftwareOnly,
            isVendor = candidate.isVendor,
        )

    fun isHardwareEligible(
        codecName: String,
        isHardwareAccelerated: Boolean,
        isSoftwareOnly: Boolean,
        isVendor: Boolean,
    ): Boolean {
        // API 29+ classification is authoritative. New Pixel generations may expose a
        // hardware implementation under a name that older releases used for software.
        if (isSoftwareOnly) return false
        if (isHardwareAccelerated) return true
        // Some vendor codecs report neither flag. Accept only the conservative vendor
        // fallback, while retaining the legacy name deny-list for that ambiguous case.
        return isVendor && !isKnownSoftwareCodec(codecName)
    }

    fun isKnownSoftwareCodec(codecName: String): Boolean {
        val name = codecName.lowercase(Locale.ROOT)
        return name.startsWith("c2.google.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("omx.google.") ||
            name.contains("software") ||
            name.contains("ffmpeg") ||
            name.endsWith(".sw")
    }

    fun selectSoftwareDecoders(
        candidates: List<CodecCandidate>,
        mimeType: String,
        platformSoftwareFlagAvailable: Boolean = true,
    ): List<CodecCandidate> =
        if (!platformSoftwareFlagAvailable) {
            emptyList()
        } else {
            candidates
            .asSequence()
            .filter { !it.isEncoder && it.isSoftwareOnly }
            .filter { candidate ->
                candidate.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }.sortedBy { it.name }
            .toList()
        }

    fun classifyActualVideoEncodingMode(
        candidate: CodecCandidate?,
        apiLevel: Int,
    ): VideoEncoderMode {
        candidate ?: return VideoEncoderMode.UNKNOWN
        if (candidate.isSoftwareOnly) return VideoEncoderMode.SOFTWARE
        if (apiLevel >= Build.VERSION_CODES.Q && candidate.isHardwareAccelerated) {
            return VideoEncoderMode.EXPLICIT_HARDWARE
        }
        if (isKnownSoftwareCodec(candidate.name)) return VideoEncoderMode.SOFTWARE
        if (apiLevel >= Build.VERSION_CODES.Q && candidate.isVendor) {
            return VideoEncoderMode.AMBIGUOUS_VENDOR
        }
        return VideoEncoderMode.UNKNOWN
    }
}

internal class HardwareCodecCatalog(
    private val codecInfos: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList(),
) {
    private val descriptors: List<CodecCandidate> = codecInfos.map(::describe)

    fun hasHardwareEncoder(mimeType: String): Boolean =
        selectedCodecInfos(mimeType, encoder = true).isNotEmpty()

    fun selectedCodecInfos(
        mimeType: String,
        encoder: Boolean,
    ): List<MediaCodecInfo> {
        val selectedNames =
            HardwareCodecPolicy.select(descriptors, mimeType, encoder)
                .mapTo(linkedSetOf()) { it.name }
        return codecInfos.filter { it.name in selectedNames }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { descriptorFor(it).isVendor }
                    .thenBy { it.name },
            )
    }

    fun compatibleVideoDecoderInfos(
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Double,
        profile: Int? = null,
        level: Int? = null,
    ): List<MediaCodecInfo> =
        selectedCodecInfos(mimeType, encoder = false).filter { info ->
            supportsDecoderFormat(
                info = info,
                mimeType = mimeType,
                width = width,
                height = height,
                frameRate = frameRate,
                profile = profile,
                level = level,
            )
        }

    fun compatibleSoftwareVideoDecoderInfos(
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Double,
        profile: Int? = null,
        level: Int? = null,
    ): List<MediaCodecInfo> {
        val selectedNames =
            HardwareCodecPolicy.selectSoftwareDecoders(
                descriptors,
                mimeType,
                platformSoftwareFlagAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            )
                .mapTo(linkedSetOf()) { it.name }
        return codecInfos
            .filter { it.name in selectedNames }
            .filter { info ->
                supportsDecoderFormat(
                    info = info,
                    mimeType = mimeType,
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    profile = profile,
                    level = level,
                )
            }.sortedBy { it.name }
    }

    fun compatibleVideoEncoderInfos(
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Double,
        bitrate: Int,
    ): List<MediaCodecInfo> =
        selectedCodecInfos(mimeType, encoder = true).filter { info ->
            runCatching {
                val capabilities = info.getCapabilitiesForType(mimeType)
                val video = capabilities.videoCapabilities ?: return@runCatching false
                val encoder = capabilities.encoderCapabilities ?: return@runCatching false
                val supportsSurface =
                    capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                val supportsBitrate = bitrate <= 0 || video.bitrateRange.contains(bitrate)
                val supportsVbr =
                    encoder.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                supportsSurface &&
                    supportsBitrate &&
                    supportsVbr &&
                    supportsSizeAndRate(video, width, height, frameRate)
            }.getOrDefault(false)
        }

    fun videoEncoderMode(codecName: String): VideoEncoderMode {
        val descriptor =
            descriptors.firstOrNull {
                it.isEncoder && (it.name == codecName || it.canonicalName == codecName)
            }
        return HardwareCodecPolicy.classifyActualVideoEncodingMode(descriptor, Build.VERSION.SDK_INT)
    }

    fun candidateSummary(
        mimeType: String,
        encoder: Boolean,
    ): String {
        val all =
            descriptors.filter { descriptor ->
                descriptor.isEncoder == encoder &&
                    descriptor.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        return if (all.isEmpty()) {
            "none"
        } else {
            all.joinToString(separator = ";") { candidate ->
                "${candidate.name}[canonical=${candidate.canonicalName},alias=${candidate.isAlias}," +
                    "hardware=${candidate.isHardwareAccelerated}," +
                    "software=${candidate.isSoftwareOnly},vendor=${candidate.isVendor}," +
                    "eligible=${HardwareCodecPolicy.select(listOf(candidate), mimeType, encoder).isNotEmpty()}]"
            }
        }
    }

    private fun supportsSizeAndRate(
        info: MediaCodecInfo,
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Double,
    ): Boolean =
        runCatching {
            val video = info.getCapabilitiesForType(mimeType).videoCapabilities
                ?: return@runCatching false
            supportsSizeAndRate(video, width, height, frameRate)
        }.getOrDefault(false)

    private fun supportsDecoderFormat(
        info: MediaCodecInfo,
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Double,
        profile: Int?,
        level: Int?,
    ): Boolean =
        runCatching {
            val capabilities = info.getCapabilitiesForType(mimeType)
            fun supports(candidateWidth: Int, candidateHeight: Int): Boolean {
                val format = MediaFormat.createVideoFormat(mimeType, candidateWidth, candidateHeight)
                if (frameRate > 0.0) {
                    format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate.toFloat())
                }
                profile?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }
                level?.let { format.setInteger(MediaFormat.KEY_LEVEL, it) }
                return capabilities.isFormatSupported(format)
            }
            supports(width, height) || supports(height, width)
        }.getOrDefault(false)

    private fun supportsSizeAndRate(
        video: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        frameRate: Double,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        fun supports(candidateWidth: Int, candidateHeight: Int): Boolean =
            if (frameRate > 0.0) {
                video.areSizeAndRateSupported(candidateWidth, candidateHeight, frameRate)
            } else {
                video.isSizeSupported(candidateWidth, candidateHeight)
            }
        return supports(width, height) || supports(height, width)
    }

    private fun descriptorFor(info: MediaCodecInfo): CodecCandidate =
        descriptors.first { it.name == info.name }

    private fun describe(info: MediaCodecInfo): CodecCandidate {
        val knownSoftware = HardwareCodecPolicy.isKnownSoftwareCodec(info.name)
        val hardware =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !knownSoftware
            }
        val software =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                knownSoftware
            }
        val vendor =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isVendor
            } else {
                !knownSoftware
            }
        return CodecCandidate(
            name = info.name,
            isEncoder = info.isEncoder,
            isHardwareAccelerated = hardware,
            isSoftwareOnly = software,
            isVendor = vendor,
            supportedTypes = info.supportedTypes.toSet(),
            canonicalName =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.canonicalName else info.name,
            isAlias = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias,
        )
    }
}

internal class HardwareVideoEncoderSelector(
    private val catalog: HardwareCodecCatalog,
    private val allowedCodecNames: Set<String>,
    private val logger: (String) -> Unit,
) : EncoderSelector {
    override fun selectEncoderInfos(mimeType: String): ImmutableList<MediaCodecInfo> {
        val selected =
            catalog.selectedCodecInfos(mimeType, encoder = true)
                .filter { it.name in allowedCodecNames }
        logger("video encoder candidates mime=$mimeType selected=${selected.joinToString { it.name }}")
        return ImmutableList.copyOf(selected)
    }
}

internal class ModeVideoDecoderSelector(
    private val allowedCodecNames: Set<String>,
    private val mode: VideoDecoderMode,
    private val logger: (String) -> Unit,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val defaults =
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
        if (!mimeType.startsWith("video/")) return defaults
        val matching =
            defaults.filter {
                it.name in allowedCodecNames &&
                    when (mode) {
                        VideoDecoderMode.HARDWARE ->
                            HardwareCodecPolicy.isHardwareEligible(
                                codecName = it.name,
                                isHardwareAccelerated = it.hardwareAccelerated,
                                isSoftwareOnly = it.softwareOnly,
                                isVendor = it.vendor,
                            )
                        VideoDecoderMode.SOFTWARE -> it.softwareOnly
                    }
            }
        val selected =
            when (mode) {
                VideoDecoderMode.HARDWARE ->
                    matching.sortedWith(
                        compareByDescending<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                            it.vendor
                        }.thenBy { it.name },
                    )
                VideoDecoderMode.SOFTWARE -> matching.sortedBy { it.name }
            }
        logger(
            "video decoder candidates mode=${mode.wireName} mime=$mimeType " +
                "default=${defaults.joinToString { it.name }} " +
                "selected=${selected.joinToString { it.name }}",
        )
        return selected
    }
}

internal class LoggingEncoderFactory(
    private val delegate: Codec.EncoderFactory,
    private val logger: (String) -> Unit,
    private val onVideoEncoderCreated: (String) -> Unit = {},
) : Codec.EncoderFactory {
    override fun createForAudioEncoding(
        requestedFormat: Format,
        logSessionId: LogSessionId?,
    ): Codec =
        delegate.createForAudioEncoding(requestedFormat, logSessionId).also { codec ->
            logger("actual audio encoder name=${codec.name} format=${codec.configurationFormat}")
        }

    override fun createForVideoEncoding(
        requestedFormat: Format,
        logSessionId: LogSessionId?,
    ): Codec =
        delegate.createForVideoEncoding(requestedFormat, logSessionId).also { codec ->
            logger("actual video encoder name=${codec.name} format=${codec.configurationFormat}")
            onVideoEncoderCreated(codec.name)
        }

    override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

    override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
}
