package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.core.CommandResult
import com.kevinluo.autoglm.history.HistoryToolStatus
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolTraceTest {
    @Test fun `local Android tools request device presentation`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("home", "android_system_key", """{"key":"HOME"}""")), finalResponse())
        assertTrue(h.run().success)
        assertTrue(h.coordinator.snapshot.value.deviceToolsRequested)
    }

    @Test fun `function computer batch uses the native mapper driver and function screenshot output`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2", function("double", "android_computer",
            """{"actions":[{"type":"double_click","x":10,"y":20}]}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 1) { h.device.doubleTap(15, 30) }
        coVerify { h.client.request(match { input ->
            input.any { item ->
                val json = item.toString()
                json.contains("function_call_output") && json.contains("double") && json.contains("input_image")
            }
        }, "r2") }
    }

    @Test fun `preflight rejection identifies failed call and skipped earlier command without phone input`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1",
            function("home", "android_system_key", """{"key":"HOME"}"""),
            function("bad", "android_wait", """{"duration_ms":60001,"reason":null}""")))
        assertFalse(h.run().success)
        coVerify { h.history.recordToolCall(match { it.callId == "bad" && it.status == HistoryToolStatus.FAILED && it.actions.isEmpty() }, null, null) }
        coVerify { h.history.recordToolCall(match { it.callId == "home" && it.status == HistoryToolStatus.SKIPPED && it.actions.isEmpty() }, null, null) }
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        coVerify(exactly = 0) { h.screenshots.capture() }
    }

    @Test fun `native trace uses pre action image and normalized completed coordinates with separate after image`() = runTest {
        val h = RunnerHarness(this)
        val before = h.frame
        val after = h.frame.copy(base64Data = "after-image")
        coEvery { h.screenshots.capture() } returnsMany listOf(before, after)
        h.respond(screenshotRequest(), nativeResponse("r2", "tap-call", tapAction(10, 20)), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.history.recordToolCall(match { call ->
            call.callId == "tap-call" && call.name == "computer" && call.stepNumber == 2 && call.runId.isNotBlank() &&
                call.status == HistoryToolStatus.SUCCEEDED && call.arguments.contains("click") &&
                !call.result.contains(before.base64Data) && call.actions == listOf(AgentAction.Tap(14, 16))
        }, before, after) }
        coVerify(exactly = 2) { h.screenshots.capture() }
    }

    @Test fun `failed partial native call retains only completed actions and records failure before turn step save`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.device.tap(30, 45) } returns CommandResult(1)
        h.respond(screenshotRequest(), nativeResponse("r2", "partial", tapAction(10, 20), tapAction(20, 30)))
        assertFalse(h.run().success)
        coVerify { h.history.recordToolCall(match { call ->
            call.callId == "partial" && call.status == HistoryToolStatus.FAILED &&
                call.actions == listOf(AgentAction.Tap(14, 16)) && call.result.contains("exitCode=1")
        }, h.frame, null) }
        coVerify(exactly = 1) { h.screenshots.capture() }
        coVerify(exactly = 0) { h.history.recordStep(2, any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { h.device.tap(15, 30) }
    }

    @Test fun `new instruction traces skipped calls without invented actions or extra pure text screenshots`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.client.request(any(), null) } coAnswers {
            assertTrue(h.coordinator.submit("new instruction"))
            functionResponse("r1", function("old-key", "android_system_key", """{"key":"HOME"}"""))
        }
        coEvery { h.client.request(any(), "r1") } returns finalResponse()
        assertTrue(h.run().success)
        coVerify { h.history.recordToolCall(match { it.callId == "old-key" && it.status == HistoryToolStatus.SKIPPED && it.actions.isEmpty() }, null, null) }
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify(exactly = 0) { h.device.pressKey(any()) }
    }

    @Test fun `cancel after completed native input persists cancellation and never replays`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.device.tap(any(), any()) } coAnswers { h.coordinator.stop(); CommandResult(0) }
        h.respond(screenshotRequest(), nativeResponse("r2", "stopped", tapAction()))
        assertFalse(h.run().success)
        coVerify { h.history.recordToolCall(match { it.callId == "stopped" && it.status == HistoryToolStatus.CANCELLED && it.actions.size == 1 }, h.frame, null) }
        coVerify(exactly = 1) { h.device.tap(any(), any()) }
        coVerify(exactly = 1) { h.screenshots.capture() }
    }

    @Test fun `trace persistence failure cannot rerun completed input`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.history.recordToolCall(any(), any(), any()) } throws java.io.IOException("disk full")
        h.respond(functionResponse("r1", function("home", "android_system_key", """{"key":"HOME"}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 1) { h.device.pressKey(3) }
        coVerify(exactly = 2) { h.client.request(any(), any()) }
    }

    @Test fun `function input acknowledgement survives cancellation when overlay scope exits`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.device.pressKey(3) } coAnswers { h.coordinator.stop(); CommandResult(0) }
        h.respond(functionResponse("r1", function("home", "android_system_key", """{"key":"HOME"}""")))
        assertFalse(h.run().success)
        coVerify { h.history.recordToolCall(match {
            it.callId == "home" && it.status == HistoryToolStatus.CANCELLED && it.actions == listOf(AgentAction.Home)
        }, null, null) }
        coVerify(exactly = 1) { h.device.pressKey(3) }
        coVerify(exactly = 0) { h.screenshots.capture() }
    }
}
