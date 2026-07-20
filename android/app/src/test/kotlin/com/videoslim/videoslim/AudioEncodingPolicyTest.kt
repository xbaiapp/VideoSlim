package com.videoslim.videoslim

import android.media.metrics.LogSessionId
import androidx.media3.common.Format
import androidx.media3.transformer.Codec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEncodingPolicyTest {
    @Test
    fun `force flag requires audio encoding even when delegate would transmux`() {
        val factory =
            LoggingEncoderFactory(
                delegate = StubEncoderFactory(audioNeedsEncoding = false),
                logger = {},
                forceAudioEncoding = true,
            )

        assertTrue(factory.audioNeedsEncoding())
    }

    @Test
    fun `without force flag wrapper preserves delegate audio decision`() {
        assertFalse(
            LoggingEncoderFactory(
                delegate = StubEncoderFactory(audioNeedsEncoding = false),
                logger = {},
            ).audioNeedsEncoding(),
        )
        assertTrue(
            LoggingEncoderFactory(
                delegate = StubEncoderFactory(audioNeedsEncoding = true),
                logger = {},
            ).audioNeedsEncoding(),
        )
    }

    @Test
    fun `M2 reencode mode is the only video request audio mode that forces encoding`() {
        assertFalse(shouldForceAudioEncoding(AudioMode.COPY))
        assertTrue(shouldForceAudioEncoding(AudioMode.REENCODE))
        assertFalse(shouldForceAudioEncoding(AudioMode.REMOVE))
    }

    private class StubEncoderFactory(
        private val audioNeedsEncoding: Boolean,
    ) : Codec.EncoderFactory {
        override fun createForAudioEncoding(
            requestedFormat: Format,
            logSessionId: LogSessionId?,
        ): Codec = error("not called")

        override fun createForVideoEncoding(
            requestedFormat: Format,
            logSessionId: LogSessionId?,
        ): Codec = error("not called")

        override fun audioNeedsEncoding(): Boolean = audioNeedsEncoding
    }
}
