package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.computer.protocol.*
import com.kevinluo.autoglm.history.HistoryToolStatus
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedToolsRunnerTest {
    @Test fun `hosted final records server trace without executing phone tools or replying to server call`() = runTest {
        val h = RunnerHarness(this)
        h.respond(finalResponse().copy(hostedCalls = listOf(HostedToolCall("ws_1", "web_search", "completed", "search weather", "Server result"))))
        assertTrue(h.run().success)
        coVerify(exactly = 1) { h.history.recordToolCall(match {
            it.callId == "ws_1" && it.name == "web_search" && it.status == HistoryToolStatus.SUCCEEDED
        }, null, null) }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify(exactly = 1) { h.client.request(any(), any()) }
        assertFalse(h.coordinator.snapshot.value.deviceToolsRequested)
    }

    @Test fun `hosted only response continues using cursor without fabricating function outputs`() = runTest {
        val h = RunnerHarness(this)
        h.respond(ComputerResponse("server-response", emptyList(), "", hostedCalls = listOf(
            HostedToolCall("ci_1", "code_interpreter", "completed", "print(2)", "2"))), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.client.request(match {
            it.toString().contains("Continue from the hosted tool results") && !it.toString().contains("function_call_output")
        }, "server-response") }
        coVerify(exactly = 1) { h.history.recordToolCall(match { it.callId == "ci_1" }, null, null) }
    }
}
