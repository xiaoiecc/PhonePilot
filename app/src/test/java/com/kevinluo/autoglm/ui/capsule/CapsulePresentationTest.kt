package com.kevinluo.autoglm.ui.capsule

import com.kevinluo.autoglm.core.ActiveWait
import com.kevinluo.autoglm.core.InteractionKind
import com.kevinluo.autoglm.core.PendingInteraction
import com.kevinluo.autoglm.core.RunPhase
import com.kevinluo.autoglm.core.RunSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CapsulePresentationTest {
    private fun project(run: RunSnapshot, minimized: Boolean = false, details: Boolean = false, draft: Boolean = false) =
        CapsulePresentation.from(run, minimized, details, draft)

    @Test fun `running details never expose ordinary composer`() {
        val running = RunSnapshot(phase = RunPhase.RUNNING)
        assertFalse(project(running).bodyVisible)
        assertTrue(project(running, details = true).messagesVisible)
        assertFalse(project(running, details = true).inputVisible)
        assertTrue(project(running).pauseVisible)
        assertTrue(project(running).stopVisible)
    }

    @Test fun `current description reuses original chat text rather than inserting a second copy`() {
        val original = "找到项目群，再确认最新消息的时间。".repeat(8)
        assertNull(CapsulePresentation.standaloneDescription(original, sequenceOf("Other", original)))
        assertNull(CapsulePresentation.standaloneDescription("", emptySequence()))
        assertEquals(original, CapsulePresentation.standaloneDescription(original, sequenceOf("Earlier message")))
    }

    @Test fun `pause opens composer without requiring a draft and running collapses it`() {
        val run = RunSnapshot(runId = "same", phase = RunPhase.PAUSED)
        assertTrue(project(run).bodyVisible)
        assertTrue(project(run).inputVisible)
        assertTrue(project(run).resumeVisible)
        assertFalse(project(run.copy(phase = RunPhase.RUNNING)).inputVisible)
        assertFalse(project(run.copy(phase = RunPhase.RUNNING)).bodyVisible)
    }

    @Test fun `every pending interaction opens its own controls instead of ordinary send`() {
        InteractionKind.entries.forEach { kind ->
            val run = RunSnapshot(phase = RunPhase.WAITING_INTERACTION,
                pendingInteraction = PendingInteraction(4, "r", kind, "Question", listOf("choice")))
            val state = project(run)
            assertTrue(state.bodyVisible)
            assertTrue(state.interactionVisible)
            assertFalse(state.inputVisible)
            assertFalse(state.resumeVisible)
            assertTrue(state.stopVisible)
        }
    }

    @Test fun `wait exposes skip without exposing a composer or changing task controls`() {
        val run = RunSnapshot(phase = RunPhase.RUNNING, activeWait = ActiveWait(1, "r", "loading", 0, 2000))
        val waiting = project(run)
        assertTrue(waiting.bodyVisible)
        assertTrue(waiting.waitVisible)
        assertFalse(waiting.inputVisible)
        assertTrue(waiting.pauseVisible)
        assertFalse(project(run.copy(activeWait = null)).bodyVisible)
    }

    @Test fun `cleanup and cancellation cannot offer send resume or a new conversation`() {
        listOf(RunPhase.CANCELLING, RunPhase.FINALIZING).forEach { phase ->
            val state = project(RunSnapshot(phase = phase), details = true, draft = true)
            assertFalse(state.inputVisible)
            assertFalse(state.resumeVisible)
            assertFalse(state.newConversationVisible)
        }
    }

    @Test fun `minimized projection suppresses all body controls without discarding facts`() {
        val run = RunSnapshot(phase = RunPhase.PAUSED, activeWait = ActiveWait(1, "r", "wait", 0, 2000))
        val ball = project(run, minimized = true, details = true, draft = true)
        assertFalse(ball.bodyVisible)
        assertFalse(ball.inputVisible)
        assertFalse(ball.waitVisible)
        assertTrue(project(run).inputVisible)
        assertTrue(project(run).waitVisible)
    }

    @Test fun `completed and failed conversation retain send and explicit new conversation`() {
        listOf(RunPhase.COMPLETED, RunPhase.FAILED).forEach { phase ->
            val state = project(RunSnapshot(phase = phase))
            assertTrue(state.inputVisible)
            assertTrue(state.newConversationVisible)
            assertFalse(state.stopVisible)
        }
    }
}
