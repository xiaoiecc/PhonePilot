package com.kevinluo.autoglm.screenshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.kevinluo.autoglm.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OverlayVisibilityTest {
    @Test
    fun `real coordinator nested pause resumes task instead of failing the run`() = runBlocking<Unit> {
        val window = PolicyWindow(active = false)
        var inputs = 0
        var pauseRequested = false
        lateinit var coordinator: RunCoordinator
        val runner = object : AgentRunner {
            override suspend fun run(task: String, continueConversation: Boolean, control: RunControl): RunOutcome {
                withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                    withOverlayHidden(window, checkpoint = {
                        if (!pauseRequested) { pauseRequested = true; coordinator.pauseUser() }
                        control.checkpoint()
                    }, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                        assertFalse(window.isVisible())
                        inputs++
                    }
                }
                return RunOutcome(true, "done", 1)
            }
            override fun enqueueMessage(text: String) = false
            override fun restoreConversation(session: ConversationSnapshot, messages: List<ChatMessage>) = true
            override fun snapshot() = ConversationSnapshot("chat")
            override fun canContinue() = false
            override fun cancelNetwork() = Unit
        }
        coordinator = RunCoordinator(this, { runner })
        val projection = launch(Dispatchers.Unconfined) { coordinator.snapshot.collect { window.policy.onRunChanged(it) } }
        assertTrue(coordinator.submit("task"))
        yield()
        assertEquals(RunPhase.PAUSED, coordinator.snapshot.value.phase)
        assertTrue(window.isVisible())
        assertEquals(0, inputs)
        assertTrue(coordinator.resumeUser())
        withTimeout(1000) { coordinator.snapshot.first { it.phase == RunPhase.COMPLETED } }
        assertEquals(1, inputs)
        assertEquals(0, window.policy.snapshot.value.hiddenLeaseCount)
        assertEquals(0, window.policy.snapshot.value.pauseVisibilityCount)
        projection.cancelAndJoin()
    }
    private class PolicyWindow(active: Boolean = true) : ScopedFloatingWindowController {
        val policy = com.kevinluo.autoglm.core.OverlayPolicy().apply {
            if (active) onRunChanged(com.kevinluo.autoglm.core.RunSnapshot(runId = "run", phase = com.kevinluo.autoglm.core.RunPhase.RUNNING))
        }
        var directMutations = 0
        override fun acquireHidden(): AutoCloseable = policy.acquireHide()
        override fun acquirePauseVisibility(): AutoCloseable = policy.acquirePauseVisibility()
        override fun isVisible() = policy.snapshot.value.visible
        override fun hide() { directMutations++ }
        override fun show() { directMutations++ }
        override fun showAndBringToFront() { directMutations++ }
    }

    @Test
    fun `real host uses nested policy leases without an independent show hide authority`() = runBlocking<Unit> {
        val window = PolicyWindow()
        withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
            assertFalse(window.isVisible())
            assertEquals(1, window.policy.snapshot.value.hiddenLeaseCount)
            withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                assertEquals(2, window.policy.snapshot.value.hiddenLeaseCount)
                assertFalse(window.isVisible())
            }
            assertFalse(window.isVisible())
            assertEquals(1, window.policy.snapshot.value.hiddenLeaseCount)
        }
        assertTrue(window.isVisible())
        assertEquals(0, window.policy.snapshot.value.hiddenLeaseCount)
        assertEquals(0, window.directMutations)
    }

    @Test
    fun `a paused nested scope exposes controls then resumes with hidden input exactly once`() = runBlocking<Unit> {
        // Cover both the first checkpoint (outer lease already held) and a pause that
        // arrives after the inner scope has acquired its own lease.
        for (pauseAt in listOf(1, 2)) {
            val window = PolicyWindow()
            val resume = CompletableDeferred<Unit>()
            var checkpoints = 0
            var inputs = 0
            val job = launch {
                    withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                        withOverlayHidden(window, checkpoint = {
                            if (++checkpoints == pauseAt) resume.await()
                        }, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                            assertFalse(window.isVisible())
                            inputs++
                        }
                    }
            }
            yield()
            assertTrue(window.isVisible(), "Outer leases must not hide pause controls")
            assertEquals(1, window.policy.snapshot.value.hiddenLeaseCount)
            assertEquals(0, inputs)
            assertFalse(job.isCompleted)
            resume.complete(Unit)
            withTimeout(1000) { job.join() }
            assertEquals(1, inputs)
            assertTrue(window.isVisible())
            assertEquals(0, window.policy.snapshot.value.hiddenLeaseCount)
            assertEquals(0, window.policy.snapshot.value.pauseVisibilityCount)
            assertEquals(0, window.directMutations)
        }
    }

    @Test
    fun `cancelling a nested paused scope releases visibility and hide leases without input`() = runBlocking<Unit> {
        val window = PolicyWindow()
        var inputs = 0
        val job = launch {
            withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                withOverlayHidden(window, checkpoint = { CompletableDeferred<Unit>().await() },
                    hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { inputs++ }
            }
        }
        yield()
        assertTrue(window.isVisible())
        job.cancelAndJoin()
        assertTrue(window.isVisible())
        assertEquals(0, inputs)
        assertEquals(0, window.policy.snapshot.value.hiddenLeaseCount)
        assertEquals(0, window.policy.snapshot.value.pauseVisibilityCount)
    }

    @Test
    fun `releasing a capture lease cannot reopen a window explicitly closed by the user`() = runBlocking<Unit> {
        val window = PolicyWindow()
        withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { window.policy.close() }
        assertFalse(window.isVisible())
        assertEquals(0, window.directMutations)
        val hidden = PolicyWindow(active = false)
        withOverlayHidden(hidden, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { }
        assertFalse(hidden.isVisible())
        assertEquals(0, hidden.directMutations)
    }

    @Test
    fun `paused checkpoint releases policy capture lease while awaiting user resume`() = runBlocking<Unit> {
        val window = PolicyWindow()
        val resume = CompletableDeferred<Unit>()
        var checkpoints = 0
        var inputs = 0
        val job = launch {
            withOverlayHidden(window, checkpoint = {
                if (++checkpoints == 2) resume.await()
            }, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { inputs++ }
        }
        yield()
        assertTrue(window.isVisible())
        assertEquals(0, inputs)
        resume.complete(Unit)
        job.join()
        assertTrue(window.isVisible())
        assertEquals(1, inputs)
        assertEquals(0, window.directMutations)
    }

    private class Window(var visible: Boolean = true) : FloatingWindowController {
        var shows = 0
        var hides = 0
        val firstHidden = CompletableDeferred<Unit>()
        override fun isVisible() = visible
        override fun hide() {
            hides++
            visible = false
            firstHidden.complete(Unit)
        }
        override fun show() { shows++; visible = true }
        override fun showAndBringToFront() = show()
    }

    @Test
    fun `pause arriving while hidden restores controls before awaiting and executes once after resume`() = runBlocking {
        val window = Window()
        val resume = CompletableDeferred<Unit>()
        var paused = true
        var checkpoints = 0
        var inputs = 0
        val job = launch {
            withOverlayHidden(window, checkpoint = {
                checkpoints++
                // The first checkpoint precedes hiding; pause arrives during the hide interval.
                if (checkpoints > 1 && paused) resume.await()
            }, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                assertFalse(window.visible)
                inputs++
            }
        }
        window.firstHidden.await()
        yield()
        assertTrue(window.visible, "Pause controls must remain available")
        assertEquals(0, inputs)
        paused = false
        resume.complete(Unit)
        job.join()
        assertEquals(1, inputs, "Resuming must not replay an input")
        assertTrue(window.visible)
    }

    @Test
    fun `cancellation during hide delay restores the window without executing input`() = runBlocking {
        val window = Window()
        var inputs = 0
        val job = launch {
            withOverlayHidden(window, hideDelayMs = 60_000, uiDispatcher = Dispatchers.Unconfined) { inputs++ }
        }
        window.firstHidden.await()
        job.cancelAndJoin()
        assertTrue(window.visible)
        assertEquals(1, window.shows)
        assertEquals(0, inputs)
    }

    @Test
    fun `an initially hidden window is never shown`() = runBlocking {
        val window = Window(visible = false)
        withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { }
        assertFalse(window.visible)
        assertEquals(0, window.shows)
        assertEquals(0, window.hides)
    }

    @Test
    fun `checkpoint failure while hidden restores the window and does not execute input`() {
        val window = Window()
        var inputs = 0
        var checkpoints = 0
        assertThrows<IllegalStateException> {
            runBlocking {
                withOverlayHidden(window, checkpoint = {
                    if (++checkpoints == 2) error("stopped")
                }, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { inputs++ }
            }
        }
        assertTrue(window.visible)
        assertEquals(0, inputs)
    }

    @Test
    fun `checkpoint cancellation while hidden restores the window`() {
        val window = Window()
        var checkpoints = 0
        assertThrows<CancellationException> {
            runBlocking {
                withOverlayHidden(window, checkpoint = {
                    if (++checkpoints == 2) throw CancellationException("cancelled")
                }, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) { error("Must not execute") }
            }
        }
        assertTrue(window.visible)
    }

    @Test
    fun `input failure restores the window`() {
        val window = Window()
        assertThrows<IllegalArgumentException> {
            runBlocking {
                withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                    throw IllegalArgumentException("input failed")
                }
            }
        }
        assertTrue(window.visible)
    }
}
