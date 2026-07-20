package com.videoslim.videoslim

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioExtractionPolicyTest {
    @Test
    fun `unconfirmed publication cleanup retains audio recovery evidence`() {
        assertTrue(
            shouldRetainAudioRecovery(
                PublicationCleanupException(
                    cleanupConfirmed = false,
                    cause = IOException("delete could not be confirmed"),
                ),
            ),
        )
        assertFalse(
            shouldRetainAudioRecovery(
                PublicationCleanupException(
                    cleanupConfirmed = true,
                    cause = IOException("publication failed but rollback completed"),
                ),
            ),
        )
        assertFalse(shouldRetainAudioRecovery(IOException("not a publication transaction")))
    }

    @Test
    fun `pipeline failure mapping preserves stable publication and cancellation errors`() {
        assertEquals(
            EngineErrorCode.OUTPUT_PERMISSION_LOST,
            mapAudioPipelineFailure(
                PublicationCleanupException(
                    cleanupConfirmed = true,
                    cause = OutputPermissionException("permission lost"),
                ),
                encoding = true,
            ).code,
        )
        assertEquals(
            EngineErrorCode.INSUFFICIENT_STORAGE,
            mapAudioPipelineFailure(IOException("ENOSPC"), encoding = false).code,
        )
        assertEquals(
            EngineErrorCode.CANCELLED,
            mapAudioPipelineFailure(CancellationException(), encoding = true).code,
        )
    }

    @Test
    fun `pipeline failure mapping does not expose raw IO details`() {
        val copy = mapAudioPipelineFailure(IOException("muxer internals"), encoding = false)
        val encode = mapAudioPipelineFailure(IllegalStateException("codec internals"), encoding = true)

        assertEquals(EngineErrorCode.AUDIO_OUTPUT_INVALID, copy.code)
        assertEquals(EngineErrorCode.AUDIO_OUTPUT_INVALID.defaultMessage, copy.message)
        assertEquals(EngineErrorCode.AUDIO_ENCODING_FAILED, encode.code)
        assertEquals(EngineErrorCode.AUDIO_ENCODING_FAILED.defaultMessage, encode.message)
    }
}
