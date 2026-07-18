package com.videoslim.videoslim

internal data class VideoDimensions(
    val width: Int,
    val height: Int,
)

internal object VideoGeometry {
    fun normalizeRotation(rotationDegrees: Int): Int =
        ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION

    fun displayDimensions(
        storageWidth: Int,
        storageHeight: Int,
        rotationDegrees: Int,
    ): VideoDimensions =
        when (normalizeRotation(rotationDegrees)) {
            90, 270 -> VideoDimensions(width = storageHeight, height = storageWidth)
            else -> VideoDimensions(width = storageWidth, height = storageHeight)
        }

    private const val FULL_ROTATION = 360
}
