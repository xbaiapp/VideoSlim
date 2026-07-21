package com.videoslim.videoslim

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMediaIoDispatcherTest {
    @Test
    fun `accepted commands run FIFO on one named worker`() {
        val dispatcher = AppMediaIoDispatcher(queueCapacity = 4)
        val blocker = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val threadNames = Collections.synchronizedSet(mutableSetOf<String>())

        dispatcher.submit(MediaIoOperation.OUTPUT_LOCATION_READ, {
            blocker.await()
            events += "first"
            threadNames += Thread.currentThread().name
        }) { finished.countDown() }
        dispatcher.submit(MediaIoOperation.OUTPUT_LOCATION_READ, {
            events += "second"
            threadNames += Thread.currentThread().name
        }) { finished.countDown() }
        dispatcher.submit(MediaIoOperation.OUTPUT_LOCATION_READ, {
            events += "third"
            threadNames += Thread.currentThread().name
        }) { finished.countDown() }

        blocker.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        dispatcher.shutdown()
        assertTrue(dispatcher.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(listOf("first", "second", "third"), events)
        assertEquals(1, threadNames.size)
        assertTrue(threadNames.single().startsWith("videoslim-media-io-"))
    }

    @Test
    fun `bounded saturation rejects explicitly exactly once`() {
        val dispatcher = AppMediaIoDispatcher(queueCapacity = 2)
        val blocker = CountDownLatch(1)
        val started = CountDownLatch(1)
        val acceptedDone = CountDownLatch(3)
        val rejected = mutableListOf<Result<Unit>>()

        dispatcher.submit(MediaIoOperation.MEDIA_DELETE_PREFLIGHT, {
            started.countDown()
            blocker.await()
        }) { acceptedDone.countDown() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        repeat(2) {
            dispatcher.submit(MediaIoOperation.MEDIA_DELETE_PREFLIGHT, { Unit }) {
                acceptedDone.countDown()
            }
        }
        dispatcher.submit(MediaIoOperation.MEDIA_DELETE_PREFLIGHT, { Unit }) { outcome ->
            rejected += outcome
        }

        assertEquals(1, rejected.size)
        assertTrue(rejected.single().exceptionOrNull() is AppMediaIoRejectedException)
        blocker.countDown()
        assertTrue(acceptedDone.await(10, TimeUnit.SECONDS))
        dispatcher.shutdown()
        assertTrue(dispatcher.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, rejected.size)
    }

    @Test
    fun `graceful shutdown drains accepted work and rejects later submissions`() {
        val dispatcher = AppMediaIoDispatcher(queueCapacity = 2)
        val blocker = CountDownLatch(1)
        val started = CountDownLatch(1)
        val drained = CountDownLatch(2)
        val outcomes = Collections.synchronizedList(mutableListOf<String>())

        dispatcher.submit(MediaIoOperation.OUTPUT_FOLDER_REPLACEMENT, {
            started.countDown()
            blocker.await()
            outcomes += "running"
        }) { drained.countDown() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        dispatcher.submit(MediaIoOperation.OUTPUT_LOCATION_RESET, {
            outcomes += "queued"
        }) { drained.countDown() }

        dispatcher.shutdown()
        val rejected = mutableListOf<Result<Unit>>()
        dispatcher.submit(MediaIoOperation.OUTPUT_LOCATION_READ, { Unit }, rejected::add)
        assertEquals(1, rejected.size)
        assertTrue(rejected.single().isFailure)
        assertFalse(dispatcher.awaitTermination(10, TimeUnit.MILLISECONDS))

        blocker.countDown()
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        assertTrue(dispatcher.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(listOf("running", "queued"), outcomes)
    }

    @Test
    fun `block failure is returned once on the same media worker`() {
        val dispatcher = AppMediaIoDispatcher(queueCapacity = 1)
        val delivered = CountDownLatch(1)
        val callbacks = Collections.synchronizedList(mutableListOf<Result<Unit>>())
        var blockThread: Thread? = null
        var callbackThread: Thread? = null

        dispatcher.submit(MediaIoOperation.VIDEO_METADATA, {
            blockThread = Thread.currentThread()
            throw IllegalStateException("broken provider")
        }) { outcome ->
            callbackThread = Thread.currentThread()
            callbacks += outcome
            delivered.countDown()
        }

        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        dispatcher.shutdown()
        assertTrue(dispatcher.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, callbacks.size)
        assertEquals("broken provider", callbacks.single().exceptionOrNull()?.message)
        assertSame(blockThread, callbackThread)
    }
}
