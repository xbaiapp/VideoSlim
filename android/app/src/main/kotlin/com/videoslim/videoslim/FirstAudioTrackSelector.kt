package com.videoslim.videoslim

import android.content.Context
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

internal data class AudioTrackCoordinate(
    val rendererIndex: Int,
    val groupIndex: Int,
    val trackIndex: Int,
)

internal fun firstAudioTrackCoordinate(
    rendererTypes: List<Int>,
    groupTrackCounts: List<List<Int>>,
): AudioTrackCoordinate? {
    rendererTypes.forEachIndexed { rendererIndex, rendererType ->
        if (rendererType != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        groupTrackCounts.getOrNull(rendererIndex).orEmpty().forEachIndexed { groupIndex, trackCount ->
            if (trackCount > 0) return AudioTrackCoordinate(rendererIndex, groupIndex, 0)
        }
    }
    return null
}

/**
 * Media3 1.10.1's default asset-loader selector sets force-highest-supported-bitrate, so it does
 * not implement the M3 "first source audio track" contract when multiple tracks are present.
 */
internal class FirstAudioTrackSelector(context: Context) : DefaultTrackSelector(context) {
    @Throws(ExoPlaybackException::class)
    override fun selectAudioTrack(
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        parameters: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val rendererTypes =
            (0 until mappedTrackInfo.rendererCount).map(mappedTrackInfo::getRendererType)
        val groupTrackCounts =
            (0 until mappedTrackInfo.rendererCount).map { rendererIndex ->
                val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
                (0 until groups.length).map { groupIndex -> groups[groupIndex].length }
            }
        val coordinate = firstAudioTrackCoordinate(rendererTypes, groupTrackCounts) ?: return null
        val group = mappedTrackInfo.getTrackGroups(coordinate.rendererIndex)[coordinate.groupIndex]
        return Pair(
            ExoTrackSelection.Definition(group, intArrayOf(coordinate.trackIndex), C.SELECTION_REASON_INITIAL),
            coordinate.rendererIndex,
        )
    }
}
