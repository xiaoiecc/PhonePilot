package com.kevinluo.autoglm.task

import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.ui.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskProjectionTest {
    @Test fun `every interaction has the same task status on every surface`() {
        val expected = mapOf(InteractionKind.CONFIRM to TaskStatus.WAITING_CONFIRMATION,
            InteractionKind.CHOICE to TaskStatus.WAITING_CONFIRMATION,
            InteractionKind.TAKEOVER to TaskStatus.WAITING_TAKEOVER,
            InteractionKind.TEXT to TaskStatus.WAITING_FOR_USER)
        expected.forEach { (kind, status) ->
            val pending = PendingInteraction(1, "run", kind, "question")
            val projection = TaskExecutionState(RunSnapshot(runId = "run", phase = RunPhase.WAITING_INTERACTION, pendingInteraction = pending))
            assertEquals(status, projection.status)
            assertEquals(pending, projection.pendingInteraction)
            assertTrue(projection.isExecuting)
        }
    }

    @Test fun `cancelling retains busy presentation until the coordinator publishes terminal`() {
        assertEquals(TaskStatus.CANCELLING, TaskExecutionState(RunSnapshot(phase = RunPhase.CANCELLING)).status)
        assertTrue(TaskExecutionState(RunSnapshot(phase = RunPhase.CANCELLING)).isExecuting)
        assertEquals(TaskStatus.CANCELLING, TaskExecutionState(RunSnapshot(phase = RunPhase.FINALIZING)).status)
        assertTrue(TaskExecutionState(RunSnapshot(phase = RunPhase.FINALIZING)).isExecuting)
        assertFalse(TaskExecutionState(RunSnapshot(phase = RunPhase.COMPLETED)).isExecuting)
        assertFalse(TaskExecutionState(RunSnapshot(phase = RunPhase.FAILED)).isExecuting)
    }

    @Test fun `projection reads live source without a collector or second mutable cache`() {
        val source = MutableStateFlow(RunSnapshot())
        val projection = ProjectedStateFlow(source, ::TaskExecutionState)
        assertEquals(TaskStatus.IDLE, projection.value.status)
        source.value = RunSnapshot(phase = RunPhase.RUNNING, stepNumber = 7, conversationId = "chat", totalInputTokens = 1234)
        assertEquals(7, projection.value.stepNumber)
        assertEquals("chat", projection.value.conversationId)
        assertEquals(1234L, projection.value.totalInputTokens)
        source.value = source.value.copy(phase = RunPhase.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, projection.value.status)
    }

    @Test fun `history step equality remains data only`() {
        assertEquals(TaskStep(1, "thinking", "action"), TaskStep(1, "thinking", "action"))
    }
}
