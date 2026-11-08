package com.kevinluo.autoglm.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlacementCoordinatorTest {
    @Test fun `latest progress survives thinking and next step until replaced but never leaks into a new run`() = runTest {
        val runner = Runner()
        val finish = CompletableDeferred<Unit>()
        runner.body = { control -> finish.await(); RunOutcome(true, "done", 2) }
        val c = RunCoordinator(this, { runner })
        c.submit("start"); runCurrent()
        runner.control.emit(RunProgress.StepStarted(1))
        runner.control.emit(RunProgress.Action("按名称查找项目群"))
        runner.control.emit(RunProgress.StepStarted(2))
        runner.control.emit(RunProgress.Thinking("正在核对界面"))
        assertEquals("按名称查找项目群", c.snapshot.value.currentAction)
        assertEquals("", c.snapshot.value.steps.last().action)
        runner.control.emit(RunProgress.Action("打开唯一匹配的会话"))
        assertEquals("打开唯一匹配的会话", c.snapshot.value.currentAction)
        finish.complete(Unit); runCurrent()
        assertTrue(c.submit("next task", forceNew = true))
        assertEquals("", c.snapshot.value.currentAction)
        c.stop(); runCurrent()
    }

    private class Runner : AgentRunner {
        lateinit var control: RunControl
        var accepted = true
        var messages = 0
        var body: suspend (RunControl) -> RunOutcome = { awaitCancellation() }
        override suspend fun run(input: ChatInput, continueConversation: Boolean, control: RunControl): RunOutcome {
            this.control = control
            return body(control)
        }
        override fun enqueueMessage(input: ChatInput): Boolean = accepted.also { if (it) messages++ }
        override fun restoreConversation(session: ConversationSnapshot, messages: List<ChatMessage>) = true
        override fun snapshot() = ConversationSnapshot("chat")
        override fun canContinue() = true
        override fun cancelNetwork() {}
    }

    @Test fun `send and resume is atomic and rejected input keeps manual pause`() = runTest {
        val runner = Runner()
        val c = RunCoordinator(this, { runner })
        c.submit("start"); runCurrent(); c.pauseUser(); c.pauseForDraft()
        runner.accepted = false
        assertFalse(c.submit("change", resumePaused = true))
        assertEquals(setOf(PauseReason.USER, PauseReason.DRAFT), c.snapshot.value.pauseReasons)
        runner.accepted = true
        assertTrue(c.submit("change", resumePaused = true))
        assertEquals(RunPhase.RUNNING, c.snapshot.value.phase)
        assertEquals(1, runner.messages)
        c.stop(); runCurrent()
    }

    @Test fun `ordinary submit still preserves manual pause and cannot bypass confirmation`() = runTest {
        val runner = Runner()
        runner.body = { it.interact(InteractionKind.CONFIRM, "确认"); awaitCancellation() }
        val c = RunCoordinator(this, { runner })
        c.submit("start"); runCurrent()
        assertFalse(c.submit("yes", resumePaused = true))
        assertNotNull(c.snapshot.value.pendingInteraction)
        c.stop(); runCurrent()
    }

    @Test fun `switch works during pause and never clears that pause`() = runTest {
        val runner = Runner()
        val modes = mutableListOf<ExecutionMode>()
        val c = RunCoordinator(this, { runner }, switchPlacement = { _, mode -> modes += mode })
        c.submit("start"); runCurrent(); c.pauseUser()
        val id = c.snapshot.value.runId!!
        assertTrue(c.requestExecutionMode(id, ExecutionMode.BACKGROUND)); runCurrent()
        assertEquals(listOf(ExecutionMode.BACKGROUND), modes)
        assertEquals(RunPhase.PAUSED, c.snapshot.value.phase)
        assertEquals(1L, c.snapshot.value.placementGeneration)
        assertTrue(c.requestExecutionMode(id, ExecutionMode.FOREGROUND)); runCurrent()
        assertEquals(RunPhase.PAUSED, c.snapshot.value.phase)
        assertFalse(c.requestExecutionMode("old-run", ExecutionMode.BACKGROUND))
        c.stop(); runCurrent()
    }

    @Test fun `migration waits for input receipt and rejects old observation afterwards`() = runTest {
        val runner = Runner()
        val inputStarted = CompletableDeferred<Unit>()
        val inputDone = CompletableDeferred<Unit>()
        var switched = false
        runner.body = { control ->
            control.deviceOperation { inputStarted.complete(Unit); inputDone.await() }
            awaitCancellation()
        }
        val c = RunCoordinator(this, { runner }, switchPlacement = { _, _ -> switched = true })
        c.submit("start"); runCurrent(); inputStarted.await()
        assertTrue(c.requestExecutionMode(c.snapshot.value.runId!!, ExecutionMode.BACKGROUND))
        runCurrent(); assertFalse(switched)
        inputDone.complete(Unit); runCurrent(); assertTrue(switched)
        var staleExecuted = false
        val stale = launch {
            try {
                runner.control.deviceOperation(0) { staleExecuted = true }
                fail<Unit>("old observation must be rejected")
            } catch (_: ExecutionTargetChangedException) { }
        }
        runCurrent(); stale.join(); assertFalse(staleExecuted)
        assertEquals(RunPhase.RUNNING, c.snapshot.value.phase)
        c.stop(); runCurrent()
    }

    @Test fun `switch failure pauses and resume explicitly verifies recovery before execution`() = runTest {
        val runner = Runner()
        var broken = true
        val c = RunCoordinator(this, { runner }, switchPlacement = { _, _ -> check(!broken) })
        c.submit("start"); runCurrent()
        val id = c.snapshot.value.runId!!
        c.requestExecutionMode(id, ExecutionMode.BACKGROUND); runCurrent()
        assertEquals(RunPhase.PAUSED, c.snapshot.value.phase)
        assertNotNull(c.snapshot.value.placementError)
        assertTrue(c.resumeUser()); runCurrent()
        assertEquals(RunPhase.PAUSED, c.snapshot.value.phase)
        assertNotNull(c.snapshot.value.placementError)
        broken = false
        assertTrue(c.resumeUser()); runCurrent()
        assertNull(c.snapshot.value.placementError)
        assertEquals(RunPhase.RUNNING, c.snapshot.value.phase)
        c.stop(); runCurrent()
    }

    @Test fun `send and resume queues input until placement recovery succeeds without replaying it`() = runTest {
        val runner = Runner()
        var broken = true
        val c = RunCoordinator(this, { runner }, switchPlacement = { _, _ -> check(!broken) })
        c.submit("start"); runCurrent()
        val id = c.snapshot.value.runId!!
        c.requestExecutionMode(id, ExecutionMode.BACKGROUND); runCurrent()
        assertTrue(c.submit("continue after recovery", resumePaused = true))
        assertEquals(0, runner.messages)
        runCurrent()
        assertEquals(RunPhase.PAUSED, c.snapshot.value.phase)
        assertEquals(0, runner.messages)
        assertFalse(c.submit("duplicate", resumePaused = true))
        broken = false
        assertTrue(c.resumeUser()); runCurrent()
        assertEquals(RunPhase.RUNNING, c.snapshot.value.phase)
        assertEquals(1, runner.messages)
        c.stop(); runCurrent()
    }

    @Test fun `screen off migrates to background and only that automatic migration returns on unlock`() = runTest {
        val runner = Runner()
        val modes = mutableListOf<ExecutionMode>()
        val c = RunCoordinator(this, { runner }, switchPlacement = { _, mode -> modes += mode })
        c.submit("start"); runCurrent()
        val id = c.snapshot.value.runId!!
        assertTrue(c.requestExecutionMode(id, ExecutionMode.BACKGROUND, ScreenEvent.SCREEN_OFF)); runCurrent()
        assertEquals(listOf(ExecutionMode.BACKGROUND), modes)
        assertTrue(c.requestExecutionMode(id, ExecutionMode.FOREGROUND, ScreenEvent.USER_PRESENT)); runCurrent()
        assertEquals(listOf(ExecutionMode.BACKGROUND, ExecutionMode.FOREGROUND), modes)
        assertTrue(c.requestExecutionMode(id, ExecutionMode.BACKGROUND)); runCurrent()
        assertFalse(c.requestExecutionMode(id, ExecutionMode.FOREGROUND, ScreenEvent.USER_PRESENT))
        c.stop(); runCurrent()
    }

    @Test fun `stop waits for migration cleanup and late receipt cannot revive run`() = runTest {
        val runner = Runner()
        val finish = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var finalized = 0
        val c = RunCoordinator(this, { runner }, onFinalize = { _, _ -> finalized++ }, switchPlacement = { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); finish.await() }
        })
        c.submit("start"); runCurrent()
        c.requestExecutionMode(c.snapshot.value.runId!!, ExecutionMode.BACKGROUND); runCurrent(); entered.await()
        c.stop(); runCurrent()
        assertEquals(RunPhase.FINALIZING, c.snapshot.value.phase)
        assertFalse(c.submit("new")); assertEquals(0, finalized)
        finish.complete(Unit); runCurrent()
        assertEquals(1, finalized)
        assertEquals(RunPhase.FAILED, c.snapshot.value.phase)
        assertEquals(ExecutionMode.FOREGROUND, c.snapshot.value.executionMode)
        assertFalse(c.snapshot.value.placementSwitching)
    }
}
