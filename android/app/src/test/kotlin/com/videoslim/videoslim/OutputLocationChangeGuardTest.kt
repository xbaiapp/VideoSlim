package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputLocationChangeGuardTest {
    @Test
    fun `running task rejects replacement and reset without invoking either mutation`() {
        val guard = OutputLocationChangeGuard(hasActiveRunningTask = { true })
        var mutationCount = 0

        val replacement = guard.replaceCustomFolder { mutationCount += 1 }
        val reset = guard.resetToDefault { mutationCount += 1 }

        assertEquals(0, mutationCount)
        listOf(replacement, reset).forEach { rejection ->
            requireNotNull(rejection)
            assertEquals(OutputLocationStore.ERROR_UNKNOWN, rejection.code)
            assertEquals("视频处理期间不能更改保存位置", rejection.message)
        }
    }

    @Test
    fun `replacement and reset proceed when registry has no running task`() {
        val guard = OutputLocationChangeGuard(hasActiveRunningTask = { false })
        val mutations = mutableListOf<String>()

        val replacement = guard.replaceCustomFolder { mutations += "replacement" }
        val reset = guard.resetToDefault { mutations += "reset" }

        assertNull(replacement)
        assertNull(reset)
        assertEquals(listOf("replacement", "reset"), mutations)
    }
}
