package com.videoslim.videoslim

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineLogRoutingTest {
    @Test
    fun `running snapshots use task keyed progress while terminal snapshots are protected`() {
        val protectedMessages = mutableListOf<String>()
        val progressMessages = mutableListOf<Pair<String, String>>()
        val running =
            TaskRuntimeSnapshot(
                taskId = "task-1",
                percent = 42.0,
                state = TaskRuntimeSnapshot.STATE_RUNNING,
                phase = TaskRuntimeSnapshot.PHASE_ENCODING,
                sourceUri = "content://source",
                outputFileName = "output.mp4",
                startedAtEpochMs = 1L,
            )
        val terminal =
            running.copy(
                state = TaskRuntimeSnapshot.STATE_FAILED,
                phase = TaskRuntimeSnapshot.PHASE_FINISHED,
                errorCode = EngineErrorCode.UNKNOWN.wireName,
                errorMessage = "failed",
            )

        routeEngineSnapshotLog(
            running,
            "running",
            protectedMessages::add,
        ) { taskId, message -> progressMessages += taskId to message }
        routeEngineSnapshotLog(
            terminal,
            "terminal",
            protectedMessages::add,
        ) { taskId, message -> progressMessages += taskId to message }

        assertEquals(listOf("task-1" to "running"), progressMessages)
        assertEquals(listOf("terminal"), protectedMessages)
    }
}
