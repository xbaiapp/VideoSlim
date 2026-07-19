package com.videoslim.videoslim

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerGrantPolicyTest {
    @Test
    fun `persistable read requires both returned flags`() {
        assertTrue(
            PickerGrantPolicy.shouldTakePersistableRead(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            ),
        )
        assertFalse(
            PickerGrantPolicy.shouldTakePersistableRead(
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        assertFalse(
            PickerGrantPolicy.shouldTakePersistableRead(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            ),
        )
    }
}
