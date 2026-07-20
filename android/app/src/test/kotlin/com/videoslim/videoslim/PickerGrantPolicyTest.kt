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

    @Test
    fun `output folder requires persistent read and write grants`() {
        val allRequired =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        assertTrue(OutputFolderGrantPolicy.canPersistWrite(allRequired))
        assertFalse(
            OutputFolderGrantPolicy.canPersistWrite(
                allRequired and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv(),
            ),
        )
        assertFalse(
            OutputFolderGrantPolicy.canPersistWrite(
                allRequired and Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv(),
            ),
        )
        assertFalse(
            OutputFolderGrantPolicy.canPersistWrite(
                allRequired and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv(),
            ),
        )
    }

    @Test
    fun `output folder display name is bounded and strips controls`() {
        assertTrue(
            OutputFolderGrantPolicy.safeDisplayName("  Family\n Videos\u0000 ") ==
                "Family Videos",
        )
        assertTrue(OutputFolderGrantPolicy.safeDisplayName(null) == "已选择的文件夹")
        assertTrue(OutputFolderGrantPolicy.safeDisplayName("x".repeat(500)).length == 200)
    }
}
