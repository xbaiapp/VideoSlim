package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockGuardTest {
    @Test
    fun `acquires once for the matching task and releases idempotently`() {
        val handle = FakeWakeLockHandle()
        val guard = WakeLockGuard(handle)

        guard.acquire("task-1", 1000L)
        assertEquals(1, handle.acquireCalls)
        assertTrue(handle.isHeld)
        assertFalse(guard.release("other"))
        assertTrue(handle.isHeld)
        assertTrue(guard.release("task-1"))
        assertFalse(handle.isHeld)
        assertFalse(guard.release("task-1"))
        assertEquals(1, handle.releaseCalls)
    }

    @Test
    fun `rejects duplicate ownership and invalid timeout`() {
        val handle = FakeWakeLockHandle()
        val guard = WakeLockGuard(handle)

        assertRejected { guard.acquire("", 1000L) }
        assertRejected { guard.acquire("task-1", 0L) }
        guard.acquire("task-1", 1000L)
        assertRejected { guard.acquire("task-2", 1000L) }
        guard.releaseAll()
    }

    @Test
    fun `failed acquisition never records ownership`() {
        val handle = FakeWakeLockHandle(failAcquire = true)
        val guard = WakeLockGuard(handle)

        try {
            guard.acquire("task-1", 1000L)
            throw AssertionError("Expected acquisition failure")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertFalse(guard.release("task-1"))
    }

    @Test
    fun `release all contains platform failures and clears ownership`() {
        val handle = FakeWakeLockHandle(failRelease = true)
        val guard = WakeLockGuard(handle)
        guard.acquire("task-1", 1000L)

        guard.releaseAll()

        assertFalse(guard.release("task-1"))
        assertEquals(1, handle.releaseCalls)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}

private class FakeWakeLockHandle(
    private val failAcquire: Boolean = false,
    private val failRelease: Boolean = false,
) : WakeLockHandle {
    override var isHeld: Boolean = false
    var acquireCalls = 0
    var releaseCalls = 0

    override fun acquire(timeoutMs: Long) {
        acquireCalls += 1
        if (failAcquire) throw IllegalStateException("acquire failed")
        isHeld = true
    }

    override fun release() {
        releaseCalls += 1
        if (failRelease) throw IllegalStateException("release failed")
        isHeld = false
    }
}
