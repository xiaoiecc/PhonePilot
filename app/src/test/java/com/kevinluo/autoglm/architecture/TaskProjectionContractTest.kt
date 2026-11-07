package com.kevinluo.autoglm.architecture

import com.kevinluo.autoglm.core.InteractionKind
import com.kevinluo.autoglm.core.PendingInteraction
import com.kevinluo.autoglm.core.RunPhase
import com.kevinluo.autoglm.core.RunSnapshot
import com.kevinluo.autoglm.task.TaskExecutionState
import com.kevinluo.autoglm.ui.TaskStatus
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskProjectionContractTest {
    @Test
    fun `every pending interaction kind projects the same wait to all views`() {
        val expected = mapOf(
            InteractionKind.CONFIRM to TaskStatus.WAITING_CONFIRMATION,
            InteractionKind.CHOICE to TaskStatus.WAITING_CONFIRMATION,
            InteractionKind.TAKEOVER to TaskStatus.WAITING_TAKEOVER,
            InteractionKind.TEXT to TaskStatus.WAITING_FOR_USER,
        )
        expected.forEach { (kind, status) ->
            val pending = PendingInteraction(10, "run", kind, "prompt", listOf("a", "b"))
            val source = RunSnapshot("run", RunPhase.WAITING_INTERACTION, pendingInteraction = pending)
            val projection = TaskExecutionState(source)
            assertEquals(status, projection.status)
            assertSame(pending, projection.pendingInteraction)
            assertTrue(projection.isExecuting)
        }
    }

    @Test
    fun `task projection has only its immutable source and derives lifecycle readiness`() {
        val expected = mapOf(
            RunPhase.IDLE to TaskStatus.IDLE,
            RunPhase.STARTING to TaskStatus.RUNNING,
            RunPhase.RUNNING to TaskStatus.RUNNING,
            RunPhase.PAUSED to TaskStatus.PAUSED,
            RunPhase.CANCELLING to TaskStatus.CANCELLING,
            RunPhase.FINALIZING to TaskStatus.CANCELLING,
            RunPhase.COMPLETED to TaskStatus.COMPLETED,
            RunPhase.FAILED to TaskStatus.FAILED,
        )
        expected.forEach { (phase, status) ->
            val source = RunSnapshot("run", phase)
            val projection = TaskExecutionState(source)
            assertSame(source, projection.run)
            assertEquals(status, projection.status)
            assertEquals(phase.isActive, projection.isExecuting)
        }
        val fields = TaskExecutionState::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(listOf("run"), fields.map { it.name })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
        assertTrue(TaskExecutionState::class.java.methods.none { it.name.startsWith("set") && it.parameterCount > 0 })
    }
}
