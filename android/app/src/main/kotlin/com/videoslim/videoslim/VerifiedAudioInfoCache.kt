package com.videoslim.videoslim

/**
 * One-shot process-local handoff from the private-output verifier to Flutter's immediate
 * post-success audio metadata readback.
 *
 * The durable/publication contracts remain authoritative. A process restart, URI mismatch, or
 * non-success snapshot simply misses this cache and falls back to a physical provider read.
 */
internal class VerifiedAudioInfoCache {
    private val lock = Any()
    private var current: Entry? = null

    fun store(
        outputUri: String,
        metadata: AudioMetadata,
    ) {
        require(outputUri.isNotBlank()) { "Verified audio output URI must not be blank" }
        synchronized(lock) {
            current = Entry(outputUri, metadata)
        }
    }

    fun take(outputUri: String): AudioMetadata? =
        synchronized(lock) {
            val entry = current?.takeIf { it.outputUri == outputUri } ?: return@synchronized null
            current = null
            entry.metadata
        }

    private data class Entry(
        val outputUri: String,
        val metadata: AudioMetadata,
    )
}

/**
 * Rebinds verified private-temp metadata to the exact public URI/name only for the matching
 * successful audio snapshot. Internal physical-sample evidence is intentionally not exposed on
 * the platform channel because [AudioMetadata.toChannelMap] emits only public AudioInfo fields.
 */
internal fun takeVerifiedPublishedAudioInfo(
    outputUri: String,
    snapshot: TaskRuntimeSnapshot?,
    cache: VerifiedAudioInfoCache,
): Map<String, Any?>? {
    if (
        snapshot?.taskKind != TaskKind.AUDIO_EXTRACTION ||
        snapshot.state != TaskRuntimeSnapshot.STATE_SUCCESS ||
        snapshot.outputUri != outputUri
    ) {
        return null
    }
    val metadata = cache.take(outputUri) ?: return null
    return metadata
        .copy(
            sourceUri = outputUri,
            fileName = snapshot.outputFileName,
        ).toChannelMap()
}
