package com.videoslim.videoslim

internal data class CropNdc(
    val left: Float,
    val right: Float,
    val bottom: Float,
    val top: Float,
)

internal data class MappedCrop(
    val displayCrop: CropRect,
    val ndc: CropNdc,
    val outputDimensions: VideoDimensions,
    val rotationDegrees: Int,
)

/** Maps a top-left-origin display-pixel rectangle to Media3 Crop NDC. */
internal object CropGeometryMapper {
    fun map(
        crop: CropRect,
        displayWidth: Int,
        displayHeight: Int,
        rotationDegrees: Int,
    ): MappedCrop {
        if (displayWidth <= 0 || displayHeight <= 0) invalidCrop()
        val right = crop.left.toLong() + crop.width.toLong()
        val bottom = crop.top.toLong() + crop.height.toLong()
        if (
            crop.left < 0 ||
            crop.top < 0 ||
            crop.width <= 0 ||
            crop.height <= 0 ||
            right > displayWidth.toLong() ||
            bottom > displayHeight.toLong()
        ) {
            invalidCrop()
        }

        val evenWidth = crop.width - crop.width % 2
        val evenHeight = crop.height - crop.height % 2
        if (evenWidth < MIN_CROP_DIMENSION || evenHeight < MIN_CROP_DIMENSION) {
            invalidCrop()
        }
        val evenCrop =
            crop.copy(
                width = evenWidth,
                height = evenHeight,
            )
        val leftNdc = pixelToNdc(evenCrop.left, displayWidth)
        val rightNdc = pixelToNdc(evenCrop.left + evenCrop.width, displayWidth)
        // Flutter crop coordinates grow down. Media3 Crop expects bottom then top in an
        // NDC coordinate system whose y axis grows up.
        val topNdc = -pixelToNdc(evenCrop.top, displayHeight)
        val bottomNdc = -pixelToNdc(evenCrop.top + evenCrop.height, displayHeight)

        return MappedCrop(
            displayCrop = evenCrop,
            ndc =
                CropNdc(
                    left = leftNdc,
                    right = rightNdc,
                    bottom = bottomNdc,
                    top = topNdc,
                ),
            outputDimensions = VideoDimensions(evenWidth, evenHeight),
            rotationDegrees = VideoGeometry.normalizeRotation(rotationDegrees),
        )
    }

    private fun pixelToNdc(value: Int, dimension: Int): Float =
        (value.toDouble() * 2.0 / dimension.toDouble() - 1.0).toFloat()

    private fun invalidCrop(): Nothing =
        throw CropMappingException(
            EngineFailure(EngineErrorCode.INVALID_CROP, "裁剪区域无效，请重新框选"),
        )

    private const val MIN_CROP_DIMENSION = 64
}

internal class CropMappingException(
    val failure: EngineFailure,
) : IllegalArgumentException(failure.message)
