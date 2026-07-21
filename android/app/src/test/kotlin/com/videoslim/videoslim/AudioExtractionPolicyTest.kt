package com.videoslim.videoslim

import androidx.media3.transformer.ExportException
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
    fun `Media3 1_10_1 format failures keep stable decoder and encoder codes`() {
        listOf(
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ).forEach { errorCode ->
            assertEquals(
                EngineErrorCode.AUDIO_DECODING_FAILED,
                mapAudioExportFailure(errorCode).code,
            )
        }
        listOf(
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ).forEach { errorCode ->
            assertEquals(
                EngineErrorCode.AUDIO_ENCODING_FAILED,
                mapAudioExportFailure(errorCode).code,
            )
        }
    }

    @Test
    fun `source SecurityException maps to stable permission loss`() {
        val mapped = audioSourcePermissionException(SecurityException("provider detail"))

        assertEquals(AudioMetadataException.SOURCE_PERMISSION_LOST, mapped.code)
        assertEquals("无法访问音频文件", mapped.message)
    }

    @Test
    fun `readable source whose audio samples are not exposed maps to audio read failure`() {
        val preparation =
            mapAudioMetadataPreparationFailure(
                AudioMetadataException(
                    AudioMetadataException.NO_READABLE_SAMPLES,
                    "无法读取音轨样本",
                ),
            )
        val copy =
            mapAudioPipelineFailure(
                NoReadableAudioSamplesException(),
                encoding = false,
            )

        assertEquals(EngineErrorCode.AUDIO_DECODING_FAILED, preparation.code)
        assertEquals(EngineErrorCode.AUDIO_DECODING_FAILED.defaultMessage, preparation.message)
        assertEquals(EngineErrorCode.AUDIO_DECODING_FAILED, copy.code)
        assertEquals(EngineErrorCode.AUDIO_DECODING_FAILED.defaultMessage, copy.message)
    }

    @Test
    fun `lossless extractor boundary requires supported AAC profile evidence`() {
        assertTrue(
            isSupportedLosslessCopyFormat(
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.AAC_PROFILE_LC,
            ),
        )
        assertTrue(
            isSupportedLosslessCopyFormat(
                AudioOutputVerifier.AAC_MIME,
                AudioOutputVerifier.AAC_PROFILE_HE,
            ),
        )
        assertFalse(isSupportedLosslessCopyFormat(AudioOutputVerifier.AAC_MIME, null))
        assertFalse(isSupportedLosslessCopyFormat(AudioOutputVerifier.AAC_MIME, 39))
        assertFalse(
            isSupportedLosslessCopyFormat("audio/opus", AudioOutputVerifier.AAC_PROFILE_LC),
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
