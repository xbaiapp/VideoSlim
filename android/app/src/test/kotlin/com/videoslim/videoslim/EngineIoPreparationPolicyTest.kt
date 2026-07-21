package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineIoPreparationPolicyTest {
    @Test
    fun `destination validation runs before media preparation`() {
        val events = mutableListOf<String>()

        EngineIoPreparationPolicy.prepare(
            validateDestination = { events += "destination" },
            prepareMedia = { events += "media" },
        )

        assertEquals(listOf("destination", "media"), events)
    }

    @Test
    fun `destination validation failure prevents media preparation`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("destination unavailable")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                EngineIoPreparationPolicy.prepare(
                    validateDestination = {
                        events += "destination"
                        throw failure
                    },
                    prepareMedia = { events += "media" },
                )
            }

        assertSame(failure, thrown)
        assertEquals(listOf("destination"), events)
    }
}
