package com.kevinluo.autoglm.computer.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Both Chat and floating Send reach the same coordinator.submit default command. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAgentConversationContinuationTest {
    @Test fun `ordinary second send after success retains conversation messages history and cursor`() = runTest {
        val h = RunnerHarness(this)
        h.respond(finalResponse("first-response", "First answer"))
        assertTrue(h.run("First message").success)
        val conversationId = h.runner.snapshot().id
        h.respond(finalResponse("second-response", "Second answer"))
        assertTrue(h.run("Follow up").success)
        assertEquals(conversationId, h.runner.snapshot().id)
        assertEquals(listOf("First message", "First answer", "Follow up", "Second answer"), h.coordinator.messages.value.map { it.text })
        coVerify(exactly = 1) { h.client.request(match { it.toString().contains("Follow up") }, "first-response") }
        coVerify(exactly = 1) { h.history.startTask("First message", conversationId) }
        coVerify(exactly = 1) { h.history.startTask("Follow up", conversationId) }
    }

    @Test fun `send after failed tool turn retains visible conversation while abandoning unsafe cursor and outputs`() = runTest {
        val h = RunnerHarness(this)
        var requests = 0
        coEvery { h.client.request(any(), any()) } coAnswers {
            if (++requests == 1) functionResponse("unsafe-response", function("home", "android_system_key", """{"key":"HOME"}"""), text = "Opening home")
            else throw IOException("Network unavailable")
        }
        assertFalse(h.run("Original request").success)
        val conversationId = h.runner.snapshot().id
        assertNull(h.runner.snapshot().previousResponseId)
        assertTrue(h.runner.canContinue())
        assertTrue(h.coordinator.canContinue)
        h.respond(finalResponse("recovered-response", "Continued answer"))
        assertTrue(h.run("Continue here").success)
        assertEquals(conversationId, h.runner.snapshot().id)
        assertEquals(listOf("Original request", "Opening home", "Continue here", "Continued answer"), h.coordinator.messages.value.map { it.text })
        coVerify(exactly = 1) { h.client.request(match {
            val text = it.toString()
            text.contains("Earlier visible conversation") && text.contains("Original request") && text.contains("Opening home") &&
                text.contains("Continue here") && !text.contains("function_call_output") && !text.contains("unsafe-response")
        }, null) }
        coVerify(exactly = 1) { h.history.startTask("Continue here", conversationId) }
        coVerify(exactly = 1) { h.device.pressKey(3) }
    }

    @Test fun `explicit new conversation clears old visible messages identity and protocol context`() = runTest {
        val h = RunnerHarness(this)
        assertFalse(h.runner.canContinue())
        h.respond(finalResponse("old-response", "Old answer"))
        assertTrue(h.run("Old message").success)
        val oldId = h.runner.snapshot().id
        h.respond(finalResponse("new-response", "New answer"))
        assertTrue(h.run("New message", forceNew = true).success)
        assertNotEquals(oldId, h.runner.snapshot().id)
        assertEquals(listOf("New message", "New answer"), h.coordinator.messages.value.map { it.text })
        coVerify(exactly = 1) { h.client.request(match {
            val text = it.toString()
            text.contains("New message") && !text.contains("Old message") && !text.contains("Old answer")
        }, null) }
    }
}
