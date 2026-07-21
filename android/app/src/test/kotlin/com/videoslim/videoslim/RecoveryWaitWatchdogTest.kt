package com.videoslim.videoslim

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryWaitWatchdogTest {
    @Test
    fun `schedule rejection and throw return typed rejection and permit retry`() {
        val rejected = RejectedExecutionException("scheduler rejected")
        val thrown = AssertionError("scheduler threw")
        val scheduler = ManualScheduler(ArrayDeque(listOf(rejected, thrown)))
        val watchdog = RecoveryWaitWatchdog(scheduler, TIMEOUT_MS) { _, _ -> }

        val first = watchdog.arm { error("rejected timeout must not run") }
        val second = watchdog.arm { error("thrown timeout must not run") }
        val third = watchdog.arm { error("cancelled timeout must not run") }

        assertTrue(first is RecoveryWaitWatchdogArmResult.Rejected)
        assertSame(rejected, (first as RecoveryWaitWatchdogArmResult.Rejected).error)
        assertTrue(second is RecoveryWaitWatchdogArmResult.Rejected)
        assertSame(thrown, (second as RecoveryWaitWatchdogArmResult.Rejected).error)
        assertEquals(RecoveryWaitWatchdogArmResult.Armed, third)
        watchdog.cancel()
    }

    @Test
    fun `registration cancel throw is reported and cannot block cancellation or rearm`() {
        val expected = IllegalStateException("registration cancel failed")
        val scheduler = ManualScheduler(cancelFailure = expected)
        val failures = mutableListOf<Pair<String, Throwable>>()
        val watchdog = RecoveryWaitWatchdog(scheduler, TIMEOUT_MS) { operation, error ->
            failures += operation to error
        }
        val timeouts = AtomicInteger()

        assertEquals(RecoveryWaitWatchdogArmResult.Armed, watchdog.arm { timeouts.incrementAndGet() })
        watchdog.cancel()
        scheduler.fireEvenIfCancelled()

        assertEquals(0, timeouts.get())
        assertEquals(1, failures.size)
        assertEquals("recovery wait watchdog cancellation", failures.single().first)
        assertSame(expected, failures.single().second)
        assertEquals(RecoveryWaitWatchdogArmResult.Armed, watchdog.arm { timeouts.incrementAndGet() })
        watchdog.cancel()
        assertEquals(2, failures.size)
    }

    @Test
    fun `synchronous timeout is one shot and late registration cancel throw is contained`() {
        val expected = IllegalStateException("late registration cancel failed")
        val scheduler = ManualScheduler(cancelFailure = expected, fireSynchronously = true)
        val failures = mutableListOf<Pair<String, Throwable>>()
        val watchdog = RecoveryWaitWatchdog(scheduler, TIMEOUT_MS) { operation, error ->
            failures += operation to error
        }
        val timeouts = AtomicInteger()

        assertEquals(RecoveryWaitWatchdogArmResult.Armed, watchdog.arm { timeouts.incrementAndGet() })
        scheduler.fireEvenIfCancelled()
        watchdog.cancel()

        assertEquals(1, timeouts.get())
        assertEquals(listOf("recovery wait watchdog cancellation"), failures.map { it.first })
        assertSame(expected, failures.single().second)
    }

    @Test
    fun `timeout and cancellation races resolve at most once`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val scheduler = ManualScheduler()
                val watchdog = RecoveryWaitWatchdog(scheduler, TIMEOUT_MS) { _, _ -> }
                val timeouts = AtomicInteger()
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)
                assertEquals(RecoveryWaitWatchdogArmResult.Armed, watchdog.arm { timeouts.incrementAndGet() })

                workers.execute {
                    start.await()
                    scheduler.fireEvenIfCancelled()
                    done.countDown()
                }
                workers.execute {
                    start.await()
                    watchdog.cancel()
                    done.countDown()
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))

                val resolved = timeouts.get()
                assertTrue("timeout count=$resolved", resolved in 0..1)
                scheduler.fireEvenIfCancelled()
                watchdog.cancel()
                assertEquals(resolved, timeouts.get())
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class ManualScheduler(
        private val scheduleFailures: ArrayDeque<Throwable> = ArrayDeque(),
        private val cancelFailure: Throwable? = null,
        private val fireSynchronously: Boolean = false,
    ) : CancellationWatchdogScheduler {
        private var scheduled: Scheduled? = null

        override fun schedule(
            timeoutMillis: Long,
            action: () -> Unit,
        ): CancellationWatchdogRegistration {
            require(timeoutMillis == TIMEOUT_MS)
            if (scheduleFailures.isNotEmpty()) throw scheduleFailures.removeFirst()
            val entry = Scheduled(action)
            scheduled = entry
            if (fireSynchronously) action()
            return CancellationWatchdogRegistration {
                entry.cancelled = true
                cancelFailure?.let { throw it }
            }
        }

        fun fireEvenIfCancelled() {
            scheduled?.action?.invoke()
        }

        private data class Scheduled(
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
