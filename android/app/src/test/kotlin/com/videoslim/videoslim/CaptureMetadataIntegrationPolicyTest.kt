package com.videoslim.videoslim

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the main-affine Media3 seam that local JVM tests cannot execute. */
class CaptureMetadataIntegrationPolicyTest {
    @Test
    fun `transcode uses one in-app muxer with capture policy and matching audio preflight`() {
        val source = transcodeEngineSource()

        assertTrue(source.contains("InAppMp4Muxer.Factory().getSupportedSampleMimeTypes"))
        assertTrue(source.contains("InAppMp4Muxer.Factory(captureMetadataPolicy)"))
        assertTrue(source.contains(".setMuxerFactory(muxerFactory)"))
        assertTrue(source.contains("task.captureMetadataPolicy = CaptureMetadataPolicy(metadata.captureMetadata)"))
    }

    @Test
    fun `temporary output metadata is verified before any public output allocation`() {
        val source = transcodeEngineSource()
        val completed =
            source.substring(
                source.indexOf("private fun onTransformerCompleted"),
                source.indexOf("private fun handleTransformerError"),
            )

        val resolved = completed.indexOf("resolvedMetadata()")
        val verified = completed.indexOf("captureMetadataVerifier.verify(")
        val published = completed.indexOf("mediaStoreSaver.publishVideo(")
        assertTrue(resolved >= 0)
        assertTrue(verified > resolved)
        assertTrue(published > verified)
    }

    private fun transcodeEngineSource(): String {
        var directory = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        for (attempt in 0 until 10) {
            val candidates =
                listOf(
                    directory.resolve(
                        "app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt",
                    ),
                    directory.resolve(
                        "android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt",
                    ),
                )
            candidates.firstOrNull(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: break
        }
        error("Unable to locate TranscodeEngine.kt")
    }
}
