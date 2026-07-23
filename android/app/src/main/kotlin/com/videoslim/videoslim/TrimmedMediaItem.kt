package com.videoslim.videoslim

import androidx.media3.common.MediaItem

/** Maps the validated M4-B segment to Media3 without keyframe snapping. */
internal fun clippingConfigurationFor(trim: TimeTrim?): MediaItem.ClippingConfiguration =
    if (trim == null) {
        MediaItem.ClippingConfiguration.UNSET
    } else {
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trim.startMs)
            .setEndPositionMs(trim.endMs)
            .setStartsAtKeyFrame(false)
            .build()
    }
