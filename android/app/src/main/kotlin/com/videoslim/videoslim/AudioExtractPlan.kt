package com.videoslim.videoslim

internal const val CONSERVATIVE_UNKNOWN_COPY_BITRATE_BPS = 512_000

internal data class AudioStorageTopology(
    val cacheAvailableBytes: Long,
    val publicAvailableBytes: Long,
    val sharesStoragePool: Boolean?,
)

internal data class AudioStorageEstimate(
    val audioBytes: Long,
    val upperAudioBytes: Long,
    val overheadBytes: Long,
    val estimatedMinBytes: Long,
    val estimatedMaxBytes: Long,
    val cacheRequiredBytes: Long,
    val publicRequiredBytes: Long,
    val sharedPoolRequiredBytes: Long,
)

/**
 * Pure preflight plan for one audio extraction.
 *
 * Estimates are deliberately used only for the free-space gate. They are not an output bitrate
 * contract and must not be used to reject a structurally valid completed file.
 */
internal data class AudioExtractPlan(
    val estimateBitrateBps: Int,
    val usedConservativeUnknownCopyBitrate: Boolean,
    val storageEstimate: AudioStorageEstimate,
    val hasSufficientStorage: Boolean,
) {
    companion object {
        private val allowedAacBitrates = setOf(192_000, 128_000, 96_000, 64_000)

        fun create(
            request: AudioExtractRequest,
            durationUs: Long,
            sourceAudioBitrate: Int?,
            storageTopology: AudioStorageTopology,
        ): AudioExtractPlan {
            require(durationUs >= 0L) { "durationUs must not be negative" }

            val usedConservativeUnknownCopyBitrate =
                request.mode == AudioExtractMode.COPY &&
                    (sourceAudioBitrate == null || sourceAudioBitrate <= 0)
            val estimateBitrateBps =
                when (request.mode) {
                    AudioExtractMode.COPY -> {
                        require(request.bitrate == null) { "copy extraction bitrate must be null" }
                        sourceAudioBitrate
                            ?.takeIf { it > 0 }
                            ?: CONSERVATIVE_UNKNOWN_COPY_BITRATE_BPS
                    }
                    AudioExtractMode.AAC -> {
                        val bitrate = requireNotNull(request.bitrate) { "AAC extraction bitrate is required" }
                        require(bitrate in allowedAacBitrates) { "unsupported AAC extraction bitrate" }
                        bitrate
                    }
                }

            val storageEstimate = estimateStorage(durationUs, estimateBitrateBps)
            return AudioExtractPlan(
                estimateBitrateBps = estimateBitrateBps,
                usedConservativeUnknownCopyBitrate = usedConservativeUnknownCopyBitrate,
                storageEstimate = storageEstimate,
                hasSufficientStorage = hasSufficientStorage(storageEstimate, storageTopology),
            )
        }

        private fun estimateStorage(
            durationUs: Long,
            bitrateBps: Int,
        ): AudioStorageEstimate {
            val audioBytes = bitrateDurationBytes(durationUs, bitrateBps)
            val upperAudioBytes = safeScale(audioBytes, COPY_UPPER_PERCENT, PERCENT_DENOMINATOR)
            val estimatedMinBytes = safeAdd(audioBytes, CONTAINER_OVERHEAD_BYTES)
            val estimatedMaxBytes = safeAdd(upperAudioBytes, CONTAINER_OVERHEAD_BYTES)
            val cacheRequiredBytes = safeAdd(estimatedMaxBytes, STORAGE_HEADROOM_BYTES)
            val publicRequiredBytes = safeAdd(estimatedMaxBytes, STORAGE_HEADROOM_BYTES)
            val sharedPoolRequiredBytes =
                safeAdd(
                    safeMultiply(estimatedMaxBytes, OVERLAPPING_TEMP_AND_PUBLIC_OUTPUTS),
                    STORAGE_HEADROOM_BYTES,
                )

            return AudioStorageEstimate(
                audioBytes = audioBytes,
                upperAudioBytes = upperAudioBytes,
                overheadBytes = CONTAINER_OVERHEAD_BYTES,
                estimatedMinBytes = estimatedMinBytes,
                estimatedMaxBytes = estimatedMaxBytes,
                cacheRequiredBytes = cacheRequiredBytes,
                publicRequiredBytes = publicRequiredBytes,
                sharedPoolRequiredBytes = sharedPoolRequiredBytes,
            )
        }

        private fun hasSufficientStorage(
            estimate: AudioStorageEstimate,
            topology: AudioStorageTopology,
        ): Boolean =
            if (topology.sharesStoragePool == false) {
                topology.cacheAvailableBytes >= estimate.cacheRequiredBytes &&
                    topology.publicAvailableBytes >= estimate.publicRequiredBytes
            } else {
                minOf(topology.cacheAvailableBytes, topology.publicAvailableBytes) >=
                    estimate.sharedPoolRequiredBytes
            }

        private fun bitrateDurationBytes(
            durationUs: Long,
            bitrateBps: Int,
        ): Long {
            if (durationUs == 0L || bitrateBps <= 0) return 0L

            val whole = safeMultiply(durationUs / BITS_PER_BYTE_MICROSECONDS, bitrateBps.toLong())
            val remainder =
                safeMultiply(durationUs % BITS_PER_BYTE_MICROSECONDS, bitrateBps.toLong()) /
                    BITS_PER_BYTE_MICROSECONDS
            return safeAdd(whole, remainder)
        }

        private fun safeScale(
            value: Long,
            numerator: Long,
            denominator: Long,
        ): Long {
            if (value == 0L) return 0L
            val whole = safeMultiply(value / denominator, numerator)
            val remainder = safeMultiply(value % denominator, numerator) / denominator
            return safeAdd(whole, remainder)
        }

        private fun safeMultiply(
            left: Long,
            right: Long,
        ): Long =
            when {
                left <= 0L || right <= 0L -> 0L
                left > Long.MAX_VALUE / right -> Long.MAX_VALUE
                else -> left * right
            }

        private fun safeAdd(
            left: Long,
            right: Long,
        ): Long =
            if (left >= Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        private const val BITS_PER_BYTE_MICROSECONDS = 8_000_000L
        private const val COPY_UPPER_PERCENT = 120L
        private const val PERCENT_DENOMINATOR = 100L
        private const val CONTAINER_OVERHEAD_BYTES = 4L * 1_024L * 1_024L
        private const val STORAGE_HEADROOM_BYTES = 64L * 1_024L * 1_024L
        private const val OVERLAPPING_TEMP_AND_PUBLIC_OUTPUTS = 2L
    }
}
