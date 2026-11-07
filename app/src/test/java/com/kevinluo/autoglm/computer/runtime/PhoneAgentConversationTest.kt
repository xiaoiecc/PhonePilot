package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.computer.protocol.ComputerResponse
import com.kevinluo.autoglm.core.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAgentConversationTest {
    @Test fun `follow up retains response context and immutable counters while a new chat resets both`() = runTest {
        val h = RunnerHarness(this)
        h.respond(ComputerResponse("r1", emptyList(), "first", inputTokens = 100, outputTokens = 20),
            ComputerResponse("r2", emptyList(), "second", inputTokens = 50, outputTokens = 10),
            ComputerResponse("r3", emptyList(), "new", inputTokens = 5, outputTokens = 2))
        assertTrue(h.run("first task").success)
        val first = h.runner.snapshot()
        assertTrue(h.run("continue here").success)
        val continued = h.runner.snapshot()
        assertEquals(first.id, continued.id)
        assertEquals("r2", continued.previousResponseId)
        assertEquals(150L, continued.totalInputTokens)
        assertEquals(30L, continued.totalOutputTokens)
        assertEquals("r1", first.previousResponseId, "Old snapshots must not change under a caller")
        assertEquals(100L, first.totalInputTokens)
        coVerify { h.client.request(match { it.toString().contains("continue here") }, "r1") }
        assertTrue(h.run("new task", forceNew = true).success)
        assertNotEquals(first.id, h.runner.snapshot().id)
        assertEquals(5L, h.runner.snapshot().totalInputTokens)
        assertEquals(listOf("new task", "new"), h.coordinator.messages.value.map { it.text })
    }

    @Test fun `changing fixed instructions invalidates the old protocol context`() = runTest {
        val h = RunnerHarness(this)
        h.respond(finalResponse("r1"), finalResponse("r2"))
        assertTrue(h.run().success)
        val old = h.runner.snapshot()
        h.runner.setSystemPrompt("new preferences")
        assertNull(h.runner.snapshot().previousResponseId)
        assertNotEquals(old.id, h.runner.snapshot().id)
        assertTrue(h.run("new goal").success)
        coVerify(exactly = 2) { h.client.request(any(), null) }
    }

    @Test fun `opening a saved conversation only restores messages until the user sends`() = runTest {
        val h = RunnerHarness(this)
        val saved = ConversationSnapshot("saved", "old-response", 100, 20, hasInputUsage = true, hasOutputUsage = true)
        assertTrue(h.coordinator.loadConversation { RestoredConversation(saved, listOf(ChatMessage(ChatRole.USER, "earlier"))) })
        coVerify(exactly = 0) { h.client.request(any(), any()) }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
        assertEquals("earlier", h.coordinator.messages.value.single().text)
        h.respond(ComputerResponse("new-response", emptyList(), "done", inputTokens = 50, outputTokens = 10))
        assertTrue(h.run("continue").success)
        coVerify { h.client.request(any(), "old-response") }
        assertEquals("saved", h.runner.snapshot().id)
        assertEquals(150L, h.runner.snapshot().totalInputTokens)
        verify { h.history.updateConversationSession(match { it.id == "saved" && it.previousResponseId == "new-response" }) }
    }

    @Test fun `older conversations without a cursor recover visible text and retain identity`() = runTest {
        val h = RunnerHarness(this)
        assertTrue(h.coordinator.loadConversation { RestoredConversation(ConversationSnapshot("legacy"), listOf(
            ChatMessage(ChatRole.USER, "earlier goal"), ChatMessage(ChatRole.AGENT, "earlier answer"))) })
        h.respond(finalResponse())
        assertTrue(h.run("next step").success)
        coVerify { h.client.request(match { it.toString().contains("earlier goal") && it.toString().contains("next step") }, null) }
        assertEquals("legacy", h.runner.snapshot().id)
    }

    @Test fun `text recovery after stopped restored batch includes messages since reopening`() = runTest {
        val h = RunnerHarness(this)
        assertTrue(h.coordinator.loadConversation { RestoredConversation(ConversationSnapshot("saved", "old-response"),
            listOf(ChatMessage(ChatRole.USER, "earlier goal"))) })
        h.respond(functionResponse("unfinished", function("home", "android_system_key", """{"key":"HOME"}""")))
        coEvery { h.device.pressKey(3) } coAnswers { h.coordinator.stop(); CommandResult(0) }
        assertFalse(h.run("fresh requirement after reopening").success)
        assertNull(h.runner.snapshot().previousResponseId)
        h.respond(finalResponse())
        assertTrue(h.run("try again").success)
        coVerify { h.client.request(match { it.toString().contains("fresh requirement after reopening") && it.toString().contains("earlier goal") }, null) }
    }

    @Test fun `active execution prevents loading another conversation`() = runTest {
        val h = RunnerHarness(this)
        val response = CompletableDeferred<ComputerResponse>()
        coEvery { h.client.request(any(), any()) } coAnswers { response.await() }
        h.begin()
        var loaded = false
        assertFalse(h.coordinator.loadConversation { loaded = true; RestoredConversation(ConversationSnapshot("other"), emptyList()) })
        assertFalse(loaded)
        assertTrue(h.coordinator.stop())
        assertFalse(h.settle().success)
    }
}