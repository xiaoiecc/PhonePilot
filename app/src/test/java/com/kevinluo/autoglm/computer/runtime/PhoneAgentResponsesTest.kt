package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.*
import com.kevinluo.autoglm.computer.protocol.*
import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.history.HistoryMessageRole
import com.kevinluo.autoglm.screenshot.Screenshot
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAgentResponsesTest {
    @Test fun `dynamic semantic click uses the same dispatcher trace and text only tool response`() = runTest {
        val service = mockk<PhoneAccessibilityService>(relaxed = true)
        coEvery { service.observe() } returns UiSnapshot("s1", "list")
        val command = UiCommand.ClickMatching("s1", "row", "Alice, ", SemanticMatchMode.PREFIX)
        coEvery { service.execute(command, any()) } returns "Action accepted by Android; relocated unique matching row."
        val h = RunnerHarness(this, service = service)
        h.respond(functionResponse("r1", function("match", "android_ui",
            """{"operation":"click_match","snapshot_id":"s1","node_id":"row","query":"Alice, ","match_mode":"prefix"}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 1) { service.execute(command, any()) }
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
        coVerify { h.history.recordToolCall(match { it.callId == "match" && it.arguments.contains("click_match") &&
            it.result.contains("relocated") }, null, null) }
    }

    @Test fun `ungrounded custom gesture returns a text error without an automatic screenshot`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", gesture()), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 0) { h.device.longPress(any(), any(), any()) }
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify { h.client.request(match {
            it[0].jsonObject["call_id"]?.jsonPrimitive?.content == "gesture" &&
                it[0].jsonObject["output"]?.jsonPrimitive?.content?.contains("entire batch was not executed") == true
        }, "r1") }
    }

    @Test fun `ungrounded native batch returns fresh image without executing then accepts a newly generated action`() = runTest {
        val h = RunnerHarness(this)
        h.respond(nativeResponse("r1", "ungrounded", tapAction(20, 30)),
            nativeResponse("r2", "regenerated", tapAction(10, 10)), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 0) { h.device.tap(30, 45) }
        coVerify(exactly = 1) { h.device.tap(15, 15) }
        coVerify { h.client.request(match {
            it.size == 2 && it[0].jsonObject["call_id"]?.jsonPrimitive?.content == "ungrounded" &&
                it[1].toString().contains("entire batch was not executed")
        }, "r1") }
        coVerify { h.history.recordToolCall(match {
            it.callId == "ungrounded" && it.status == com.kevinluo.autoglm.history.HistoryToolStatus.FAILED && it.actions.isEmpty()
        }, null, null) }
    }

    @Test fun `ungrounded mixed batch skips earlier functions and answers every call without replay`() = runTest {
        val h = RunnerHarness(this)
        h.respond(ComputerResponse("r1", listOf(ComputerCall("tap", listOf(obj(tapAction())), emptyList())), "",
            listOf(function("home", "android_system_key", """{"key":"HOME"}""")), listOf("home", "tap")), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
        coVerify(exactly = 1) { h.screenshots.capture() }
        coVerify { h.client.request(match {
            it.size == 3 && it[0].jsonObject["call_id"]?.jsonPrimitive?.content == "home" &&
                it[0].jsonObject["output"]?.jsonPrimitive?.content?.startsWith("ERROR:") == true &&
                it[1].jsonObject["call_id"]?.jsonPrimitive?.content == "tap"
        }, "r1") }
    }

    @Test fun `failed accessibility action invalidates visual grounding but next native call can recover`() = runTest {
        val service = mockk<PhoneAccessibilityService>(relaxed = true)
        coEvery { service.observe() } returns UiSnapshot("s1", "tree")
        coEvery { service.execute(any(), any()) } returns "ERROR: stale snapshot; observe again"
        val h = RunnerHarness(this, service = service)
        h.respond(screenshotRequest(),
            functionResponse("r2", function("ui", "android_ui", """{"operation":"click","snapshot_id":"s1","node_id":"n1","text":null}""")),
            nativeResponse("r3", "ungrounded", tapAction()), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
        coVerify(exactly = 2) { h.screenshots.capture() }
        coVerify { h.client.request(match { it.size == 2 && it[1].toString().contains("not executed") }, "r3") }
    }

    @Test fun `text final at limit succeeds without automatic screenshots and completes once`() = runTest {
        val h = RunnerHarness(this, maxSteps = 1)
        h.respond(finalResponse(text = "完成"))
        assertEquals("完成", h.run().message)
        assertEquals(1, h.completions.size)
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify(exactly = 1) { h.history.completeTask(true, "完成") }
    }

    @Test fun `tool outputs retain call ids response ids and exact physical coordinates`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), nativeResponse("r2", "tap", tapAction(), tapAction(20, 30)), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.client.request(match { it.single().jsonObject["call_id"]?.jsonPrimitive?.content == "shot" }, "r1") }
        coVerify { h.client.request(match { it.single().jsonObject["call_id"]?.jsonPrimitive?.content == "tap" }, "r2") }
        coVerify(exactly = 1) { h.device.tap(15, 15) }
        coVerify(exactly = 1) { h.device.tap(30, 45) }
        coVerify(exactly = 2) { h.screenshots.capture() }
    }

    @Test fun `pause resume retains primitive cursor without repeating first tap`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), nativeResponse("r2", "tap", tapAction(), tapAction(20, 30)), finalResponse())
        var taps = 0
        coEvery { h.device.tap(any(), any()) } coAnswers {
            if (++taps == 1) h.coordinator.pauseUser()
            CommandResult(0)
        }
        h.begin()
        assertEquals(RunPhase.PAUSED, h.coordinator.snapshot.value.phase)
        assertEquals(1, taps)
        assertTrue(h.coordinator.resumeUser())
        assertTrue(h.settle().success)
        assertEquals(2, taps)
    }

    @Test fun `stop after a partial batch prevents remainder and finalizes once`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), nativeResponse("r2", "tap", tapAction(), tapAction(20, 30)))
        coEvery { h.device.tap(any(), any()) } coAnswers { h.coordinator.stop(); CommandResult(0) }
        assertFalse(h.run().success)
        coVerify(exactly = 1) { h.device.tap(any(), any()) }
        assertEquals(1, h.completions.size)
        assertNull(h.runner.snapshot().previousResponseId)
        coVerify(exactly = 1) { h.history.completeTask(false, any()) }
    }

    @Test fun `invalid later coordinate blocks the entire native batch before input`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), nativeResponse("r2", "tap", tapAction(), tapAction(99999, 20)))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }

    @Test fun `pending safety checks prevent every native input`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), ComputerResponse("r2", listOf(ComputerCall("tap", listOf(obj(tapAction())),
            listOf(obj("""{"id":"safety","code":"sensitive","message":"review"}""")))), ""))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }

    @Test fun `sensitive fallback capture is never returned as a real screen`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.screenshots.capture() } returns h.frame.copy(isSensitive = true)
        h.respond(screenshotRequest())
        assertFalse(h.run().success)
        coVerify(exactly = 1) { h.client.request(any(), any()) }
    }

    @Test fun `native screenshot bootstraps without a prior image`() = runTest {
        val h = RunnerHarness(this)
        h.respond(nativeResponse("r1", "native-shot", """{"type":"screenshot"}"""), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.client.request(match { it.single().jsonObject["call_id"]?.jsonPrimitive?.content == "native-shot" }, "r1") }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }

    @Test fun `accessibility actions return pure text without screenshots`() = runTest {
        val service = mockk<PhoneAccessibilityService>()
        coEvery { service.observe() } returns UiSnapshot("s1", "snapshot_id=s1 node n1 button")
        coEvery { service.execute(com.kevinluo.autoglm.accessibility.UiCommand.Target("s1", "n1", com.kevinluo.autoglm.accessibility.NodeAction.Simple.CLICK), any()) } returns "snapshot_id=s2 new page"
        val h = RunnerHarness(this, service = service)
        h.respond(functionResponse("r1", function("ui", "android_ui", """{"operation":"click","snapshot_id":"s1","node_id":"n1","text":null}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.client.request(match { it.single().jsonObject["output"]?.jsonPrimitive?.content == "snapshot_id=s2 new page" }, "r1") }
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }

    @Test fun `stale accessibility target returns error without coordinate guessing`() = runTest {
        val service = mockk<PhoneAccessibilityService>()
        coEvery { service.observe() } returns UiSnapshot("s1", "tree")
        coEvery { service.execute(any(), any()) } returns "ERROR: stale snapshot; observe again"
        val h = RunnerHarness(this, service = service)
        h.respond(functionResponse("r1", function("ui", "android_ui", """{"operation":"click","snapshot_id":"s1","node_id":"n1","text":null}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.client.request(match { it.single().jsonObject["output"]?.jsonPrimitive?.content?.startsWith("ERROR:") == true }, "r1") }
        coVerify(exactly = 0) { h.screenshots.capture() }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }

    @Test fun `ordinary chat does not read the foreground phone or depend on accessibility`() = runTest {
        val service = mockk<PhoneAccessibilityService>()
        coEvery { service.observe() } throws IllegalStateException("Observation unavailable")
        val h = RunnerHarness(this, service = service)
        h.respond(finalResponse())
        assertTrue(h.run().success)
        assertEquals(1, h.completions.size)
        coVerify(exactly = 0) { service.observe() }
        coVerify(exactly = 1) { h.history.completeTask(true, any()) }
        assertFalse(h.coordinator.snapshot.value.deviceToolsRequested)
    }

    @Test fun `usage is projected separately from normal model prose`() = runTest {
        val h = RunnerHarness(this)
        h.respond(ComputerResponse("r1", emptyList(), "完成", inputTokens = 100, outputTokens = 20, cachedInputTokens = 60))
        assertTrue(h.run().success)
        assertEquals(100L, h.coordinator.snapshot.value.totalInputTokens)
        assertEquals(20L, h.coordinator.snapshot.value.totalOutputTokens)
        assertEquals("完成", h.coordinator.messages.value.last().text)
        assertEquals("完成", h.coordinator.snapshot.value.thinking)
        assertEquals(60, h.runner.snapshot().totalCachedTokens)
    }

    @Test fun `phone observation waits for actual foreground handoff acknowledgement`() = runTest {
        val handoff = kotlinx.coroutines.CompletableDeferred<Unit>()
        val service = mockk<PhoneAccessibilityService>()
        coEvery { service.execute(UiCommand.Observe(null, null), any()) } coAnswers {
            assertTrue(handoff.isCompleted)
            "snapshot_id=s1 tree"
        }
        val h = RunnerHarness(this, service = service, beforeDeviceTools = {
            it.emit(RunProgress.DeviceToolsRequested)
            handoff.await()
            it.checkpoint()
        })
        h.respond(functionResponse("r1", function("observe", "android_ui", """{"operation":"observe"}""")), finalResponse())
        assertTrue(h.coordinator.submit("task"))
        runCurrent()
        coVerify(exactly = 0) { service.execute(any(), any()) }
        assertTrue(h.coordinator.snapshot.value.deviceToolsRequested)
        handoff.complete(Unit)
        advanceUntilIdle()
        assertEquals(RunPhase.COMPLETED, h.coordinator.snapshot.value.phase)
        coVerify(exactly = 1) { service.execute(any(), any()) }
    }

    @Test fun `stop during foreground handoff never executes late phone actions`() = runTest {
        val handoff = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = RunnerHarness(this, beforeDeviceTools = { handoff.await(); it.checkpoint() })
        h.respond(functionResponse("r1", function("home", "android_system_key", """{"key":"HOME"}""")))
        h.begin()
        assertTrue(h.coordinator.stop())
        handoff.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        assertEquals(1, h.completions.size)
    }

    @Test fun `failed foreground handoff fails closed before device execution`() = runTest {
        val h = RunnerHarness(this, beforeDeviceTools = { error("Foreground handoff unavailable") })
        h.respond(functionResponse("r1", function("home", "android_system_key", """{"key":"HOME"}""")))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        assertEquals(1, h.completions.size)
        assertNull(h.runner.snapshot().previousResponseId)
    }

    @Test fun `invalid custom coordinate preflights before earlier functions execute`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2",
            function("home", "android_system_key", """{"key":"HOME"}"""), gesture(x = 99999)))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        coVerify(exactly = 0) { h.device.longPress(any(), any(), any()) }
    }

    @Test fun `a screenshot after ui mutation cannot ground later coordinates in the same response`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2", function("home", "android_system_key", """{"key":"HOME"}"""),
            function("new-shot", "android_screenshot", "{}"), gesture()))
        assertFalse(h.run().success)
        coVerify(exactly = 1) { h.device.pressKey(3) }
        coVerify(exactly = 0) { h.device.longPress(any(), any(), any()) }
    }

    @Test fun `completed gesture blocks subsequent coordinates from its old response`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2", gesture("first"), gesture("second", x = 20)))
        assertFalse(h.run().success)
        coVerify(exactly = 1) { h.device.longPress(any(), any(), any()) }
    }

    @Test fun `native input blocks later custom gesture from the same response`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), ComputerResponse("r2", listOf(ComputerCall("tap", listOf(obj(tapAction())), emptyList())), "",
            listOf(gesture()), outputOrder = listOf("tap", "gesture")))
        assertFalse(h.run().success)
        coVerify(exactly = 1) { h.device.tap(any(), any()) }
        coVerify(exactly = 0) { h.device.longPress(any(), any(), any()) }
    }

    @Test fun `rotated image cannot ground a gesture generated from old dimensions`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.screenshots.capture() } returnsMany listOf(h.frame, Screenshot("rotated", 1280, 720, 1920, 1080))
        h.respond(screenshotRequest(), functionResponse("r2", function("rotated", "android_screenshot", "{}"), gesture()))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.device.longPress(any(), any(), any()) }
    }

    @Test fun `required commentary is saved as a normal bubble without a fake operation message`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("note", "android_note", """{"message":"remember","commentary":"我先检查结果。"}""")), finalResponse())
        assertTrue(h.run().success)
        assertEquals(listOf("task", "我先检查结果。", "done"), h.coordinator.messages.value.map { it.text })
        coVerify { h.history.recordMessage(HistoryMessageRole.AGENT, "我先检查结果。") }
    }

    @Test fun `independent calls each publish their own intent before the device action`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1",
            function("first", "android_system_key", """{"key":"HOME","commentary":"先回到桌面。"}"""),
            function("second", "android_system_key", """{"key":"BACK","commentary":"再关闭当前面板。"}""")), finalResponse())
        coEvery { h.device.pressKey(3) } coAnswers {
            assertEquals("先回到桌面。", h.coordinator.messages.value.last().text)
            assertEquals("先回到桌面。", h.coordinator.snapshot.value.currentAction)
            CommandResult(0)
        }
        coEvery { h.device.pressKey(4) } coAnswers {
            assertEquals("再关闭当前面板。", h.coordinator.messages.value.last().text)
            assertEquals("再关闭当前面板。", h.coordinator.snapshot.value.currentAction)
            CommandResult(0)
        }
        assertTrue(h.run().success)
        assertEquals(1, h.coordinator.messages.value.count { it.text == "先回到桌面。" })
        assertEquals(1, h.coordinator.messages.value.count { it.text == "再关闭当前面板。" })
        coVerify(exactly = 1) { h.device.pressKey(3) }
        coVerify(exactly = 1) { h.device.pressKey(4) }
    }

    @Test fun `a missing explanation in a later call rejects the whole batch without side effects`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1",
            function("first", "android_system_key", """{"key":"HOME","commentary":"先回到桌面。"}"""),
            com.kevinluo.autoglm.computer.protocol.ComputerFunctionCall("invalid", "android_system_key", obj("""{"key":"BACK"}"""))))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        assertTrue(h.coordinator.messages.value.none { it.text == "先回到桌面。" })
        assertNull(h.runner.snapshot().previousResponseId)
    }

    @Test fun `a response generated before placement migration cannot issue nonvisual input on the new target`() = runTest {
        val h = RunnerHarness(this)
        val coordinator = RunCoordinator(this, { h.runner }, switchPlacement = { _, _ -> })
        val response = kotlinx.coroutines.CompletableDeferred<ComputerResponse>()
        coEvery { h.client.request(any(), null) } coAnswers { response.await() }
        coEvery { h.client.request(any(), "old-response") } returns finalResponse()
        assertTrue(coordinator.submit("task"))
        runCurrent()
        assertTrue(coordinator.requestExecutionMode(requireNotNull(coordinator.snapshot.value.runId), ExecutionMode.BACKGROUND))
        runCurrent()
        assertEquals(1L, coordinator.snapshot.value.placementGeneration)
        response.complete(functionResponse("old-response", function("old-key", "android_system_key",
            """{"key":"HOME","commentary":"返回桌面。"}""")))
        advanceUntilIdle()
        assertEquals(RunPhase.COMPLETED, coordinator.snapshot.value.phase)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        coVerify { h.client.request(match { input ->
            input.any { it.jsonObject["call_id"]?.jsonPrimitive?.content == "old-key" &&
                it.jsonObject["output"]!!.jsonPrimitive.content.contains("not executed", ignoreCase = true) }
        }, "old-response") }
    }

    @Test fun `visual result carries fresh semantic targets for returning to text actions`() = runTest {
        val service = mockk<PhoneAccessibilityService>(relaxed = true)
        coEvery { service.observe() } returns UiSnapshot("s-new", "tree", listOf(SemanticNode("n1", SemanticKind.BUTTON,
            label = "继续", actions = listOf(SemanticAction("click")))), 1)
        val h = RunnerHarness(this, service = service)
        h.respond(screenshotRequest(), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.client.request(match { it.size == 2 && it[1].toString().contains("s-new") && it[1].toString().contains("继续") }, "r1") }
    }

    @Test fun `maximum step stop is a single failed outcome without replay`() = runTest {
        val h = RunnerHarness(this, maxSteps = 1)
        h.respond(functionResponse("r1", function("note", "android_note", """{"message":"note"}""")))
        assertFalse(h.run().success)
        assertEquals(1, h.completions.size)
        coVerify(exactly = 1) { h.client.request(any(), any()) }
        assertNull(h.runner.snapshot().previousResponseId)
    }
}
