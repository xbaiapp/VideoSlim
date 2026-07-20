package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingServicePublicationPolicyTest {
    @Test
    fun `normal publication completion commits published recovery`() {
        assertEquals(
            RecoveryStage.PUBLISHED,
            publicationCompletionRecoveryStage(cancellationRequested = false),
        )
    }

    @Test
    fun `accepted cancellation wins completion race and retains discard intent`() {
        assertEquals(
            RecoveryStage.DISCARDING,
            publicationCompletionRecoveryStage(cancellationRequested = true),
        )
    }
}
