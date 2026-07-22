package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CropGeometryMapperTest {
    @Test
    fun `maps top-left display pixels to Media3 bottom-top NDC`() {
        val mapped =
            CropGeometryMapper.map(
                crop = CropRect(left = 100, top = 50, width = 1280, height = 720),
                displayWidth = 1920,
                displayHeight = 1080,
                rotationDegrees = 0,
            )

        assertEquals(-0.8958333f, mapped.ndc.left, 0.000001f)
        assertEquals(0.4375f, mapped.ndc.right, 0.000001f)
        assertEquals(-0.4259259f, mapped.ndc.bottom, 0.000001f)
        assertEquals(0.9074074f, mapped.ndc.top, 0.000001f)
        assertTrue(mapped.ndc.bottom < mapped.ndc.top)
        assertEquals(VideoDimensions(1280, 720), mapped.outputDimensions)
    }

    @Test
    fun `display mapping is identical for all rotation metadata values`() {
        val crop = CropRect(left = 20, top = 40, width = 640, height = 480)
        val mappings =
            listOf(0, 90, 180, 270).map { rotation ->
                CropGeometryMapper.map(crop, 1080, 1920, rotation)
            }

        mappings.drop(1).forEach { mapped ->
            assertEquals(mappings.first().displayCrop, mapped.displayCrop)
            assertEquals(mappings.first().ndc, mapped.ndc)
            assertEquals(mappings.first().outputDimensions, mapped.outputDimensions)
        }
        assertEquals(listOf(0, 90, 180, 270), mappings.map { it.rotationDegrees })
    }

    @Test
    fun `trims odd width and height only from right and bottom`() {
        val mapped =
            CropGeometryMapper.map(
                crop = CropRect(left = 3, top = 5, width = 641, height = 481),
                displayWidth = 1000,
                displayHeight = 800,
                rotationDegrees = 450,
            )

        assertEquals(CropRect(left = 3, top = 5, width = 640, height = 480), mapped.displayCrop)
        assertEquals(VideoDimensions(640, 480), mapped.outputDimensions)
        assertEquals(90, mapped.rotationDegrees)
    }

    @Test
    fun `fails closed for invalid or out-of-bounds crops`() {
        listOf(
            CropRect(left = -1, top = 0, width = 100, height = 100),
            CropRect(left = 0, top = -1, width = 100, height = 100),
            CropRect(left = 0, top = 0, width = 0, height = 100),
            CropRect(left = 0, top = 0, width = 100, height = 0),
            CropRect(left = 950, top = 0, width = 100, height = 100),
            CropRect(left = 0, top = 750, width = 100, height = 100),
            CropRect(left = 0, top = 0, width = 63, height = 100),
            CropRect(left = 0, top = 0, width = 100, height = 63),
        ).forEach { crop ->
            val error =
                try {
                    CropGeometryMapper.map(crop, 1000, 800, 0)
                    fail("Expected CropMappingException for $crop")
                    error("unreachable")
                } catch (exception: CropMappingException) {
                    exception
                }
            assertEquals(EngineErrorCode.INVALID_CROP, error.failure.code)
        }
    }
}
