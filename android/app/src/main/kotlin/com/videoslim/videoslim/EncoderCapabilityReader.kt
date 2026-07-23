package com.videoslim.videoslim

import android.annotation.TargetApi
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.RequiresApi

internal data class EncoderTypeCapability(
    val supportsCq: Boolean,
    val supportsVbr: Boolean,
    val supportsCbr: Boolean,
    val supportsQpBounds: Boolean?,
    val bitrateLower: Int,
    val bitrateUpper: Int,
    val complexityLower: Int,
    val complexityUpper: Int,
)

internal data class EncoderCodecDescriptor(
    val name: String,
    val canonicalName: String?,
    val isEncoder: Boolean,
    val isAlias: Boolean?,
    val isHardwareAccelerated: Boolean?,
    val isSoftwareOnly: Boolean?,
    val isVendor: Boolean?,
    val supportedTypes: Set<String>,
    val inspect: (String) -> EncoderTypeCapability,
)

private data class PlatformCodecIdentity(
    val canonicalName: String,
    val isAlias: Boolean,
    val isHardwareAccelerated: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean,
)

/**
 * Read-only projection of Android's declared video encoder capabilities.
 *
 * This reader only enumerates [MediaCodecList] and calls
 * [MediaCodecInfo.getCapabilitiesForType]. It deliberately has no codec
 * creation, configuration, task, service, or Transformer dependency.
 */
internal class EncoderCapabilityReader(
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val codecProvider: () -> List<EncoderCodecDescriptor> = {
        platformCodecDescriptors(apiLevel)
    },
) {
    fun read(): Map<String, Any?> {
        val entries = mutableListOf<Map<String, Any?>>()
        val descriptors =
            codecProvider()
            .asSequence()
            .filter(EncoderCodecDescriptor::isEncoder)
            .sortedBy(EncoderCodecDescriptor::name)
            .toList()
        for (descriptor in descriptors) {
            for (mimeType in TARGET_MIME_TYPES) {
                val declaredMimeType =
                    descriptor.supportedTypes.firstOrNull {
                        it.equals(mimeType, ignoreCase = true)
                    } ?: continue
                entries += inspectEntry(descriptor, mimeType, declaredMimeType)
            }
        }
        return linkedMapOf(
            "sdkInt" to apiLevel,
            "queriedMimeTypes" to TARGET_MIME_TYPES,
            "encoders" to entries,
        )
    }

    private fun inspectEntry(
        descriptor: EncoderCodecDescriptor,
        mimeType: String,
        declaredMimeType: String,
    ): Map<String, Any?> {
        val identity = identityFields(descriptor, mimeType)
        return runCatching { descriptor.inspect(declaredMimeType) }
            .fold(
                onSuccess = { capability ->
                    identity +
                        linkedMapOf(
                            "supportsCq" to capability.supportsCq,
                            "supportsVbr" to capability.supportsVbr,
                            "supportsCbr" to capability.supportsCbr,
                            "supportsQpBounds" to capability.supportsQpBounds,
                            "bitrateRange" to
                                linkedMapOf(
                                    "lower" to capability.bitrateLower,
                                    "upper" to capability.bitrateUpper,
                                ),
                            "complexityRange" to
                                linkedMapOf(
                                    "lower" to capability.complexityLower,
                                    "upper" to capability.complexityUpper,
                                ),
                            "errorCode" to null,
                        )
                },
                onFailure = {
                    identity +
                        linkedMapOf(
                            "supportsCq" to null,
                            "supportsVbr" to null,
                            "supportsCbr" to null,
                            "supportsQpBounds" to null,
                            "bitrateRange" to null,
                            "complexityRange" to null,
                            "errorCode" to "CAPABILITY_QUERY_FAILED",
                        )
                },
            )
    }

    private fun identityFields(
        descriptor: EncoderCodecDescriptor,
        mimeType: String,
    ): Map<String, Any?> {
        val platformClassification = apiLevel >= Build.VERSION_CODES.Q
        return linkedMapOf(
            "name" to descriptor.name,
            "canonicalName" to descriptor.canonicalName.takeIf { platformClassification },
            "mimeType" to mimeType,
            "isAlias" to descriptor.isAlias.takeIf { platformClassification },
            "isHardwareAccelerated" to
                descriptor.isHardwareAccelerated.takeIf { platformClassification },
            "isSoftwareOnly" to descriptor.isSoftwareOnly.takeIf { platformClassification },
            "isVendor" to descriptor.isVendor.takeIf { platformClassification },
            "classificationSource" to
                if (platformClassification) "platform" else "unavailable_pre29",
        )
    }

    companion object {
        val TARGET_MIME_TYPES: List<String> =
            listOf(
                "video/avc",
                "video/hevc",
                "video/av01",
                "video/x-vnd.on2.vp9",
            )

        private fun platformCodecDescriptors(apiLevel: Int): List<EncoderCodecDescriptor> =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.map { codecInfo ->
                val platformIdentity =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        codecInfo.platformIdentity()
                    } else {
                        null
                    }
                EncoderCodecDescriptor(
                    name = codecInfo.name,
                    canonicalName = platformIdentity?.canonicalName,
                    isEncoder = codecInfo.isEncoder,
                    isAlias = platformIdentity?.isAlias,
                    isHardwareAccelerated = platformIdentity?.isHardwareAccelerated,
                    isSoftwareOnly = platformIdentity?.isSoftwareOnly,
                    isVendor = platformIdentity?.isVendor,
                    supportedTypes = codecInfo.supportedTypes.toSet(),
                    inspect = { mimeType -> inspectPlatformType(codecInfo, mimeType, apiLevel) },
                )
            }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun MediaCodecInfo.platformIdentity(): PlatformCodecIdentity =
            PlatformCodecIdentity(
                canonicalName = canonicalName,
                isAlias = isAlias,
                isHardwareAccelerated = isHardwareAccelerated,
                isSoftwareOnly = isSoftwareOnly,
                isVendor = isVendor,
            )

        private fun inspectPlatformType(
            codecInfo: MediaCodecInfo,
            mimeType: String,
            apiLevel: Int,
        ): EncoderTypeCapability {
            val capabilities = codecInfo.getCapabilitiesForType(mimeType)
            val encoder =
                requireNotNull(capabilities.encoderCapabilities) {
                    "Missing encoder capabilities"
                }
            val video =
                requireNotNull(capabilities.videoCapabilities) {
                    "Missing video capabilities"
                }
            val bitrate = video.bitrateRange
            val complexity = encoder.complexityRange
            return EncoderTypeCapability(
                supportsCq =
                    encoder.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                    ),
                supportsVbr =
                    encoder.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    ),
                supportsCbr =
                    encoder.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    ),
                supportsQpBounds =
                    if (apiLevel >= Build.VERSION_CODES.S) {
                        supportsQpBounds(capabilities)
                    } else {
                        null
                    },
                bitrateLower = bitrate.lower,
                bitrateUpper = bitrate.upper,
                complexityLower = complexity.lower,
                complexityUpper = complexity.upper,
            )
        }

        @TargetApi(Build.VERSION_CODES.S)
        private fun supportsQpBounds(
            capabilities: MediaCodecInfo.CodecCapabilities,
        ): Boolean =
            capabilities.isFeatureSupported(
                MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds,
            )
    }
}
