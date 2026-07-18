package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    @Test
    fun `normalizes rotation into zero to 359 degrees`() {
        assertEquals(0, VideoGeometry.normalizeRotation(0))
        assertEquals(90, VideoGeometry.normalizeRotation(450))
        assertEquals(270, VideoGeometry.normalizeRotation(-90))
        assertEquals(359, VideoGeometry.normalizeRotation(-1))
    }

    @Test
    fun `swaps display dimensions for quarter-turn rotations`() {
        assertEquals(
            VideoDimensions(width = 1080, height = 1920),
            VideoGeometry.displayDimensions(
                storageWidth = 1920,
                storageHeight = 1080,
                rotationDegrees = 90,
            ),
        )
        assertEquals(
            VideoDimensions(width = 1080, height = 1920),
            VideoGeometry.displayDimensions(
                storageWidth = 1920,
                storageHeight = 1080,
                rotationDegrees = 270,
            ),
        )
    }

    @Test
    fun `retains display dimensions for other rotations`() {
        assertEquals(
            VideoDimensions(width = 1920, height = 1080),
            VideoGeometry.displayDimensions(
                storageWidth = 1920,
                storageHeight = 1080,
                rotationDegrees = 0,
            ),
        )
        assertEquals(
            VideoDimensions(width = 1920, height = 1080),
            VideoGeometry.displayDimensions(
                storageWidth = 1920,
                storageHeight = 1080,
                rotationDegrees = 180,
            ),
        )
    }
}
