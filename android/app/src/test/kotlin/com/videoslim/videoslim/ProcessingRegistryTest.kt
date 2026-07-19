package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingRegistryTest {
    @Test
    fun `reserves one task and exposes strict progress and snapshot maps`() {
        val registry = ProcessingRegistry()

        val snapshot =
            registry.reserve(
                taskId = "task-1",
                sourceUri = "content://media/source",
                outputFileName = "source_slim.mp4",
                startedAtEpochMs = 1234L,
            )

        assertEquals("task-1", snapshot.taskId)
        assertEquals(
            mapOf(
                "taskId" to "task-1",
                "percent" to 0.0,
                "state" to "running",
                "phase" to "preparing",
                "outputUri" to null,
                "outputFileName" to "source_slim.mp4",
                "errorCode" to null,
                "errorMessage" to null,
            ),
            snapshot.toProgressMap(),
        )
        assertEquals("content://media/source", snapshot.toSnapshotMap()["sourceUri"])
        assertEquals("source_slim.mp4", snapshot.toSnapshotMap()["outputFileName"])
        assertEquals(1234L, snapshot.toSnapshotMap()["startedAtEpochMs"])
        assertFalse(snapshot.toSnapshotMap().containsKey("cancelling"))
    }

    @Test
    fun `rejects a second task while running and allows a new task after terminal`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "one.mp4", 1L)

        assertRejected { registry.reserve("two", "content://two", "two.mp4", 2L) }
        assertTrue(registry.apply("one", 100.0, "success", "content://output/one"))

        val second = registry.reserve("two", "content://two", "two.mp4", 2L)
        assertEquals("two", second.taskId)
    }

    @Test
    fun `progress is monotonic bounded and isolated by task id`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "one.mp4", 1L)

        assertFalse(registry.apply("other", 50.0, "running"))
        assertTrue(registry.apply("one", 60.0, "running"))
        assertTrue(registry.apply("one", 40.0, "running"))
        assertEquals(60.0, registry.snapshot()!!.percent, 0.0)
        assertRejected { registry.apply("one", -1.0, "running") }
        assertRejected { registry.apply("one", 101.0, "running") }
    }

    @Test
    fun `running phase changes are preserved independently of percent`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "one.mp4", 1L)

        assertTrue(
            registry.apply(
                taskId = "one",
                percent = 0.0,
                state = "running",
                phase = TaskRuntimeSnapshot.PHASE_ENCODING,
            ),
        )
        assertEquals(TaskRuntimeSnapshot.PHASE_ENCODING, registry.snapshot()!!.phase)
        assertRejected {
            registry.apply(
                taskId = "one",
                percent = 0.0,
                state = "running",
                phase = "invalid",
            )
        }
    }

    @Test
    fun `terminal event is immutable and validated`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "one.mp4", 1L)

        assertRejected { registry.apply("one", 100.0, "success") }
        assertRejected {
            registry.apply(
                "one",
                70.0,
                "failed",
                errorCode = null,
                errorMessage = "failed",
            )
        }
        assertTrue(
            registry.apply(
                "one",
                70.0,
                "failed",
                errorCode = "ENCODER_UNAVAILABLE",
                errorMessage = "没有编码器",
            ),
        )
        assertFalse(registry.apply("one", 100.0, "success", "content://late"))
        assertEquals("failed", registry.snapshot()!!.state)
    }

    @Test
    fun `engine cancellation error pair is accepted without an output`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "one.mp4", 1L)

        assertTrue(
            registry.apply(
                taskId = "one",
                percent = 45.0,
                state = "cancelled",
                errorCode = "CANCELLED",
                errorMessage = "任务已取消",
            ),
        )
        assertEquals("cancelled", registry.snapshot()!!.state)
        assertEquals("CANCELLED", registry.snapshot()!!.errorCode)
    }

    @Test
    fun `observers receive the current snapshot and one notification per accepted update`() {
        val registry = ProcessingRegistry()
        val first = mutableListOf<TaskRuntimeSnapshot>()
        val second = mutableListOf<TaskRuntimeSnapshot>()
        val firstObserver: (TaskRuntimeSnapshot) -> Unit = { first += it }
        registry.addObserver(firstObserver)
        registry.reserve("one", "content://one", "one.mp4", 1L)
        registry.addObserver(second::add)
        registry.apply("one", 25.0, "running")
        registry.removeObserver(firstObserver)
        registry.apply("one", 50.0, "running")

        assertEquals(listOf(0.0, 25.0), first.map(TaskRuntimeSnapshot::percent))
        assertEquals(listOf(0.0, 25.0, 50.0), second.map(TaskRuntimeSnapshot::percent))
    }

    @Test
    fun `launch failure clears only the matching running reservation`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "one.mp4", 1L)

        assertFalse(registry.releaseLaunchFailure("other"))
        assertTrue(registry.releaseLaunchFailure("one"))
        assertNull(registry.snapshot())
    }

    @Test
    fun `published display name can replace the requested name before terminal`() {
        val registry = ProcessingRegistry()
        registry.reserve("one", "content://one", "requested.mp4", 1L)

        assertFalse(registry.updateOutputFileName("other", "actual.mp4"))
        assertTrue(registry.updateOutputFileName("one", "actual (1).mp4"))
        assertEquals("actual (1).mp4", registry.snapshot()!!.outputFileName)
        assertRejected { registry.updateOutputFileName("one", "folder/unsafe.mp4") }
        registry.apply("one", 100.0, "success", "content://output/one")
        assertFalse(registry.updateOutputFileName("one", "too-late.mp4"))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException or IllegalStateException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}
