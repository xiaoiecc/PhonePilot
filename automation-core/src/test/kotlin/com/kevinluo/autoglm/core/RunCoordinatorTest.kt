package com.kevinluo.autoglm.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunCoordinatorTest {
    private class Runner : AgentRunner {
        var session = ConversationSnapshot("session")
        var canResume = false
        var restoreAccepted = true
        var cancellations = 0
        val queued = mutableListOf<ChatInput>()
        var body: suspend (ChatInput, Boolean, RunControl) -> RunOutcome = { _, _, _ -> RunOutcome(true, "done", 1, session) }
        override suspend fun run(input: ChatInput, continueConversation: Boolean, control: RunControl): RunOutcome = body(input, continueConversation, control)
        override fun enqueueMessage(input: ChatInput): Boolean { queued += input; return true }
        override fun restoreConversation(session: ConversationSnapshot, messages: List<ChatMessage>): Boolean {
            if (!restoreAccepted) return false
            this.session = session
            canResume = true
            return true
        }
        override fun snapshot() = session
        override fun canContinue() = canResume
        override fun cancelNetwork() { cancellations++ }
    }

    @Test fun `a submission retains multiple image references in order`() = runTest {
        val runner = Runner()
        var received: ChatInput? = null
        runner.body = { input, _, control ->
            received = input
            control.emit(RunProgress.UserMessage(input.text, images = input.images))
            RunOutcome(true, "done", 1)
        }
        val coordinator = RunCoordinator(this, { runner })
        val images = listOf(ChatImage("first.webp", "image/webp"), ChatImage("second.webp", "image/webp"))
        assertTrue(coordinator.submit("", images = images))
        advanceUntilIdle()
        assertEquals(images, received!!.images)
        assertEquals(images, coordinator.messages.value.single().images)
    }

    @Test fun `terminal is published once after runner cleanup and persistence finish`() = runTest {
        val runner = Runner()
        val cleanup = CompletableDeferred<Unit>()
        val persist = CompletableDeferred<Unit>()
        var saved = 0
        runner.body = { _, _, _ ->
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        val coordinator = RunCoordinator(this, { runner }, { _, _ -> saved++; persist.await() })
        val completions = mutableListOf<RunCompletion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.completions.collect { completions += it } }
        assertTrue(coordinator.submit("first"))
        runCurrent()
        assertTrue(coordinator.stop())
        runCurrent()
        assertEquals(RunPhase.CANCELLING, coordinator.snapshot.value.phase)
        assertTrue(coordinator.isBusy)
        assertFalse(coordinator.submit("second"))
        assertEquals(0, saved)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, saved)
        assertTrue(completions.isEmpty())
        assertTrue(coordinator.isBusy)
        assertEquals(RunPhase.FINALIZING, coordinator.snapshot.value.phase)
        assertFalse(coordinator.reset())
        persist.complete(Unit)
        advanceUntilIdle()
        assertFalse(coordinator.isBusy)
        assertEquals(RunPhase.FAILED, coordinator.snapshot.value.phase)
        assertFalse(coordinator.stop())
        assertEquals(1, saved)
        assertEquals(1, completions.size)
    }

    @Test fun `stale run progress and replies cannot mutate a later run`() = runTest {
        val runner = Runner()
        var old: RunControl? = null
        runner.body = { _, _, control -> old = control; control.emit(RunProgress.UserMessage("old")); RunOutcome(true, "done", 1) }
        val coordinator = RunCoordinator(this, { runner })
        coordinator.submit("first")
        advanceUntilIdle()
        val stale = old!!
        runner.body = { _, _, control -> control.emit(RunProgress.UserMessage("new")); control.interact(InteractionKind.TEXT, "Question"); RunOutcome(true, "done", 1) }
        coordinator.submit("second", forceNew = true)
        runCurrent()
        val pending = coordinator.snapshot.value.pendingInteraction!!
        stale.emit(RunProgress.Thinking("stale"))
        stale.emit(RunProgress.AssistantMessage("stale"))
        assertFalse(coordinator.reply(stale.runId, pending.id, InteractionReply.Text("wrong")))
        assertEquals(listOf("new"), coordinator.messages.value.map { it.text })
        assertEquals("", coordinator.snapshot.value.thinking)
        assertTrue(coordinator.reply(pending.runId, pending.id, InteractionReply.Text("answer")))
        advanceUntilIdle()
    }

    @Test fun `device presentation can be requested again but stale progress is ignored`() = runTest {
        val runner = Runner()
        var old: RunControl? = null
        runner.body = { _, _, control ->
            old = control
            control.emit(RunProgress.DeviceToolsRequested)
            control.emit(RunProgress.DeviceToolsRequested)
            RunOutcome(true, "done", 1)
        }
        val coordinator = RunCoordinator(this, { runner })
        val requests = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.deviceToolRequests.collect { requests += it }
        }
        coordinator.submit("first")
        advanceUntilIdle()
        assertTrue(coordinator.snapshot.value.deviceToolsRequested)
        assertEquals(2, requests.size)

        runner.body = { _, _, _ -> RunOutcome(true, "done", 1) }
        coordinator.submit("second", forceNew = true)
        advanceUntilIdle()
        assertFalse(coordinator.snapshot.value.deviceToolsRequested)
        old!!.emit(RunProgress.DeviceToolsRequested)
        assertFalse(coordinator.snapshot.value.deviceToolsRequested)
        assertEquals(2, requests.size)
    }

    @Test fun `draft clearing and queued messages never release a manual pause`() = runTest {
        val runner = Runner()
        val request = CompletableDeferred<Unit>()
        var executed = 0
        runner.body = { _, _, control -> request.await(); control.checkpoint(); executed++; RunOutcome(true, "done", 1) }
        val coordinator = RunCoordinator(this, { runner })
        coordinator.submit("task")
        runCurrent()
        coordinator.pauseForDraft()
        coordinator.pauseUser()
        coordinator.onEditorBlur(false)
        assertEquals(setOf(PauseReason.USER), coordinator.snapshot.value.pauseReasons)
        coordinator.pauseForDraft()
        assertTrue(coordinator.submit("new requirement"))
        assertEquals(listOf("new requirement"), runner.queued.map { it.text })
        request.complete(Unit)
        runCurrent()
        assertEquals(0, executed)
        assertEquals(RunPhase.PAUSED, coordinator.snapshot.value.phase)
        coordinator.resumeUser()
        advanceUntilIdle()
        assertEquals(1, executed)
    }

    @Test fun `all interaction kinds share one owned pending reply and reject duplicate ids`() = runTest {
        for (kind in InteractionKind.entries) {
            val runner = Runner()
            var received: InteractionReply? = null
            runner.body = { _, _, control -> received = control.interact(kind, "prompt", listOf("a", "b")); RunOutcome(true, "done", 1) }
            val coordinator = RunCoordinator(this, { runner })
            coordinator.submit("task")
            runCurrent()
            val pending = coordinator.snapshot.value.pendingInteraction!!
            assertEquals(RunPhase.WAITING_INTERACTION, coordinator.snapshot.value.phase)
            assertFalse(coordinator.pauseForDraft())
            val reply = when (kind) {
                InteractionKind.CONFIRM, InteractionKind.TAKEOVER -> InteractionReply.Accepted(true)
                InteractionKind.CHOICE -> InteractionReply.Choice(1)
                InteractionKind.TEXT -> InteractionReply.Text("answer")
            }
            assertTrue(coordinator.reply(pending.runId, pending.id, reply))
            assertFalse(coordinator.reply(pending.runId, pending.id, reply))
            advanceUntilIdle()
            assertEquals(reply, received)
        }
    }

    @Test fun `sending in a text interaction replies rather than adding a stranded queue message`() = runTest {
        val runner = Runner()
        var answer: InteractionReply? = null
        runner.body = { _, _, control -> answer = control.interact(InteractionKind.TEXT, "question"); RunOutcome(true, "done", 1) }
        val coordinator = RunCoordinator(this, { runner })
        coordinator.submit("task")
        runCurrent()
        assertTrue(coordinator.submit("real answer"))
        advanceUntilIdle()
        assertEquals(InteractionReply.Text("real answer"), answer)
        assertTrue(runner.queued.isEmpty())
    }

    @Test fun `wait is skippable only by its current run and wait ids`() = runTest {
        val runner = Runner()
        var result: WaitResult? = null
        runner.body = { _, _, control -> result = control.wait(10000, "loading"); RunOutcome(true, "done", 1) }
        val coordinator = RunCoordinator(this, { runner }, clockMs = { testScheduler.currentTime })
        coordinator.submit("task")
        runCurrent()
        val wait = coordinator.snapshot.value.activeWait!!
        assertFalse(coordinator.skipWait("old-run", wait.id))
        advanceTimeBy(100)
        assertTrue(coordinator.skipWait(wait.runId, wait.id))
        advanceUntilIdle()
        assertTrue(result!!.skipped)
        assertTrue(result!!.actualMs < 10000)
        assertNull(coordinator.snapshot.value.activeWait)
    }

    @Test fun `later load and reset invalidate older asynchronous restore results`() = runTest {
        val runner = Runner()
        val firstReady = CompletableDeferred<RestoredConversation?>()
        val coordinator = RunCoordinator(this, { runner })
        var firstAccepted: Boolean? = null
        val first = launch { firstAccepted = coordinator.loadConversation { firstReady.await() } }
        runCurrent()
        assertTrue(coordinator.loadConversation { RestoredConversation(ConversationSnapshot("new"), listOf(ChatMessage(ChatRole.USER, "new"))) })
        firstReady.complete(RestoredConversation(ConversationSnapshot("old"), emptyList()))
        first.join()
        assertEquals(false, firstAccepted)
        assertEquals("new", coordinator.snapshot.value.conversationId)
        val pending = CompletableDeferred<RestoredConversation?>()
        val loading = launch { coordinator.loadConversation { pending.await() } }
        runCurrent()
        coordinator.reset()
        pending.complete(RestoredConversation(ConversationSnapshot("old"), emptyList()))
        loading.join()
        assertNull(coordinator.snapshot.value.conversationId)
        assertTrue(coordinator.messages.value.isEmpty())
    }

    @Test fun `opening offline history does not execute and restores against the later runner`() = runTest {
        var available: AgentRunner? = null
        val runner = Runner()
        var continued = false
        runner.body = { _, continuation, _ -> continued = continuation; RunOutcome(true, "done", 1, runner.session) }
        val coordinator = RunCoordinator(this, { available })
        assertTrue(coordinator.loadConversation { RestoredConversation(ConversationSnapshot("saved", "response"), listOf(ChatMessage(ChatRole.USER, "earlier"))) })
        assertTrue(coordinator.canContinue)
        assertFalse(coordinator.submit("continue"))
        available = runner
        assertTrue(coordinator.submit("continue"))
        advanceUntilIdle()
        assertTrue(continued)
        assertEquals("saved", runner.session.id)
    }

    @Test fun `cancelled before lazy run enters still persists exactly one cancellation`() = runTest {
        val runner = Runner()
        var entered = 0
        var finalized = 0
        runner.body = { _, _, _ -> entered++; RunOutcome(true, "done", 1) }
        val coordinator = RunCoordinator(this, { runner }, { _, _ -> finalized++ })
        coordinator.submit("task")
        coordinator.stop()
        advanceUntilIdle()
        assertEquals(0, entered)
        assertEquals(1, finalized)
        assertEquals(RunPhase.FAILED, coordinator.snapshot.value.phase)
    }

    @Test fun `cancelling a new lazy run cannot resurrect the previous chat cursor`() = runTest {
        val runner = Runner()
        runner.session = ConversationSnapshot("previous-chat", "previous-response")
        runner.canResume = true
        val persisted = mutableListOf<RunOutcome>()
        var entered = 0
        var continued: Boolean? = null
        runner.body = { _, continuation, _ ->
            entered++
            continued = continuation
            runner.session = ConversationSnapshot("fresh-chat", "fresh-response")
            RunOutcome(true, "done", 1, runner.session)
        }
        val coordinator = RunCoordinator(this, { runner }, { _, outcome -> persisted += outcome })
        assertTrue(coordinator.loadConversation {
            RestoredConversation(runner.session, listOf(ChatMessage(ChatRole.USER, "previous goal")))
        })
        assertTrue(coordinator.submit("a completely new goal", forceNew = true))
        assertTrue(coordinator.stop())
        advanceUntilIdle()
        assertEquals(0, entered)
        assertNull(persisted.single().session)
        assertNull(coordinator.snapshot.value.conversationId)
        assertTrue(coordinator.messages.value.isEmpty())
        assertFalse(coordinator.canContinue)
        assertTrue(coordinator.submit("new message after cancellation"))
        advanceUntilIdle()
        assertEquals(false, continued)
        assertEquals("fresh-chat", coordinator.snapshot.value.conversationId)
    }

    @Test fun `runner and persistence failures terminate without leaving an execution owner`() = runTest {
        val runner = Runner()
        var saves = 0
        runner.body = { _, _, _ -> error("backend unavailable") }
        val coordinator = RunCoordinator(this, { runner }, { _, _ -> saves++; error("storage unavailable") })
        assertTrue(coordinator.submit("task"))
        advanceUntilIdle()
        assertFalse(coordinator.isBusy)
        assertEquals(RunPhase.FAILED, coordinator.snapshot.value.phase)
        assertTrue(coordinator.snapshot.value.resultMessage.contains("保存失败"))
        assertEquals(1, saves)
        assertTrue(coordinator.reset())
    }

    @Test fun `one lifecycle stage gates startup pause interaction finalization and terminal readiness`() = runTest {
        val runner = Runner()
        val startInteraction = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        lateinit var control: RunControl
        runner.body = { _, _, owner ->
            control = owner
            startInteraction.await()
            owner.interact(InteractionKind.TAKEOVER, "manual step")
            RunOutcome(true, "done", 1, runner.session)
        }
        val coordinator = RunCoordinator(this, { runner }, { _, _ -> persisted.await() })
        assertTrue(coordinator.submit("task"))
        assertEquals(RunPhase.STARTING, coordinator.snapshot.value.phase)
        runCurrent()
        assertEquals(RunPhase.RUNNING, coordinator.snapshot.value.phase)
        assertTrue(coordinator.pauseForDraft())
        assertEquals(RunPhase.PAUSED, coordinator.snapshot.value.phase)
        assertTrue(coordinator.pauseUser())
        coordinator.onEditorBlur(false)
        assertEquals(RunPhase.PAUSED, coordinator.snapshot.value.phase)
        assertTrue(coordinator.resumeUser())
        assertEquals(RunPhase.RUNNING, coordinator.snapshot.value.phase)
        startInteraction.complete(Unit)
        runCurrent()
        assertEquals(RunPhase.WAITING_INTERACTION, coordinator.snapshot.value.phase)
        val pending = coordinator.snapshot.value.pendingInteraction!!
        assertTrue(coordinator.reply(pending.runId, pending.id, InteractionReply.Accepted(true)))
        runCurrent()
        assertEquals(RunPhase.FINALIZING, coordinator.snapshot.value.phase)
        assertTrue(coordinator.snapshot.value.phase.isActive)
        assertTrue(coordinator.isBusy)
        assertNull(coordinator.snapshot.value.pendingInteraction)
        assertFalse(coordinator.submit("new message"))
        assertFalse(coordinator.pauseUser())
        assertFalse(coordinator.pauseForDraft())
        assertFalse(coordinator.resumeUser())
        assertFalse(coordinator.stop())
        assertFalse(coordinator.reset())
        assertFalse(coordinator.canContinue)
        assertFalse(coordinator.reply(pending.runId, pending.id, InteractionReply.Accepted(true)))
        assertFalse(coordinator.skipWait(pending.runId, 1))
        control.emit(RunProgress.Thinking("late progress"))
        assertEquals("", coordinator.snapshot.value.thinking)
        persisted.complete(Unit)
        advanceUntilIdle()
        assertEquals(RunPhase.COMPLETED, coordinator.snapshot.value.phase)
        assertFalse(coordinator.snapshot.value.phase.isActive)
        assertFalse(coordinator.isBusy)
    }

    @Test fun `a pending interaction blocks even an external primitive checkpoint until its reply`() = runTest {
        val runner = Runner()
        val finish = CompletableDeferred<Unit>()
        lateinit var control: RunControl
        runner.body = { _, _, owner ->
            control = owner
            owner.interact(InteractionKind.CONFIRM, "approve")
            finish.await()
            RunOutcome(true, "done", 1)
        }
        val coordinator = RunCoordinator(this, { runner })
        coordinator.submit("task")
        runCurrent()
        var primitiveInputs = 0
        val primitive = launch { control.checkpoint(); primitiveInputs++ }
        runCurrent()
        assertEquals(0, primitiveInputs)
        val pending = coordinator.snapshot.value.pendingInteraction!!
        coordinator.reply(pending.runId, pending.id, InteractionReply.Accepted(true))
        runCurrent()
        assertEquals(1, primitiveInputs)
        primitive.join()
        finish.complete(Unit)
        advanceUntilIdle()
    }
}
