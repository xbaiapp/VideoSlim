package com.videoslim.videoslim

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationBoundaryTest {
    @Test
    fun `repeated cancellation stays deferred until publication cleanup completes`() {
        val boundary = PublicationBoundary()

        boundary.begin()

        assertTrue(boundary.shouldDeferCancellation())
        assertTrue(boundary.shouldDeferCancellation())

        boundary.complete()

        assertFalse(boundary.shouldDeferCancellation())
    }
}
