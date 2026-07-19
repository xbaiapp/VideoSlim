package com.videoslim.videoslim

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceAccessProbePolicyTest {
    @Test
    fun `readable source does not override media failure`() {
        val result =
            SourceAccessProbeResult(
                status = SourceAccessStatus.READABLE,
                persistedReadPermission = true,
                statSize = 123L,
                seekable = true,
            )

        assertNull(result.toEngineFailure())
    }

    @Test
    fun `permission loss asks the user to reselect instead of blaming corruption`() {
        val failure =
            SourceAccessProbeResult(
                status = SourceAccessStatus.PERMISSION_DENIED,
                persistedReadPermission = false,
            ).toEngineFailure()

        assertEquals(EngineErrorCode.SOURCE_PERMISSION_LOST, failure?.code)
    }

    @Test
    fun `missing and provider failures remain distinct`() {
        val missing =
            SourceAccessProbeResult(
                status = SourceAccessStatus.NOT_FOUND,
                persistedReadPermission = true,
            ).toEngineFailure()
        val provider =
            SourceAccessProbeResult(
                status = SourceAccessStatus.PROVIDER_IO,
                persistedReadPermission = true,
            ).toEngineFailure()

        assertEquals(EngineErrorCode.SOURCE_UNAVAILABLE, missing?.code)
        assertEquals(EngineErrorCode.SOURCE_PROVIDER_FAILED, provider?.code)
    }

    @Test
    fun `unknown readable size is accepted`() {
        val result =
            SourceAccessProbeResult(
                status = SourceAccessStatus.READABLE,
                persistedReadPermission = false,
                statSize = null,
                seekable = true,
                bytesRead = 16,
            )

        assertNull(result.toFailureAtExport(null))
    }

    @Test
    fun `source size change at export overrides a codec failure`() {
        val baseline =
            SourceAccessProbeResult(
                status = SourceAccessStatus.READABLE,
                persistedReadPermission = true,
                statSize = 123L,
            )
        val changed = baseline.copy(statSize = 124L)

        assertEquals(
            EngineErrorCode.SOURCE_UNAVAILABLE,
            changed.toFailureAtExport(baseline)?.code,
        )
    }

    @Test
    fun `wrapped source exceptions retain stable access categories`() {
        assertEquals(
            EngineErrorCode.SOURCE_PERMISSION_LOST,
            sourceAccessFailureFrom(IllegalStateException(SecurityException("denied")))?.code,
        )
        assertEquals(
            EngineErrorCode.SOURCE_UNAVAILABLE,
            sourceAccessFailureFrom(IllegalStateException(FileNotFoundException("gone")))?.code,
        )
        assertEquals(
            EngineErrorCode.SOURCE_PROVIDER_FAILED,
            sourceAccessFailureFrom(IllegalStateException(IOException("provider")))?.code,
        )
        assertNull(sourceAccessFailureFrom(IllegalStateException("other")))
    }
}