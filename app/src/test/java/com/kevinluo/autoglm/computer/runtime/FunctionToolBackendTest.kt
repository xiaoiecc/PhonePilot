package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.accessibility.UiSnapshot
import com.kevinluo.autoglm.computer.protocol.*
import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.input.InputResult
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FunctionToolBackendTest {
    // MockK evaluates argument matchers against initial user input as well as tool outputs.
    private fun JsonArray.singleOutput(): String? = ((singleOrNull() as? JsonObject)?.get("output") as? JsonPrimitive)?.content

    @Test fun `launch list system key and note use original call ids through one dispatcher`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1",
            function("launch", "android_launch_app", """{"app":"Example"}"""),
            function("apps", "android_list_apps", "{}"),
            function("volume", "android_system_key", """{"key":"VOLUME_DOWN"}"""),
            function("note", "android_note", """{"message":"remember"}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify { h.device.launchApp("com.example") }
        coVerify(exactly = 1) { h.device.pressKey(25) }
        coVerify { h.client.request(match { input ->
            input.mapNotNull { it.jsonObject["call_id"]?.jsonPrimitive?.content } == listOf("launch", "apps", "volume", "note") &&
                input[1].jsonObject["output"]!!.jsonPrimitive.content.contains("Example: com.example")
        }, "r1") }
    }

    @Test fun `typed launch failure ends the run rather than guessing successful text`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.device.launchApp(any()) } returns CommandResult(1, "ordinary text", "")
        h.respond(functionResponse("r1", function("launch", "android_launch_app", """{"app":"Example"}""")))
        assertFalse(h.run().success)
        coVerify(exactly = 1) { h.client.request(any(), any()) }
    }

    @Test fun `missing launch target returns a recoverable tool error without privileged input`() = runTest {
        val h = RunnerHarness(this)
        every { h.apps.resolvePackageName(any()) } returns null
        h.respond(functionResponse("r1", function("launch", "android_launch_app", """{"app":"Missing"}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 0) { h.device.launchApp(any()) }
        coVerify { h.client.request(match { it.singleOutput()?.startsWith("ERROR:") == true }, "r1") }
    }

    @Test fun `system BACK and native BACK execute exactly one identical Android key`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("back", "android_system_key", """{"key":"BACK"}""")), finalResponse("r2"))
        assertTrue(h.run().success)
        h.respond(screenshotRequest("r3"), nativeResponse("r4", "native-back", """{"type":"keypress","keys":["BACK"]}"""), finalResponse("r5"))
        assertTrue(h.run(forceNew = true).success)
        coVerify(exactly = 2) { h.device.pressKey(4) }
        coVerify(exactly = 0) { h.device.pressKey(111) }
    }

    @Test fun `input modes preserve empty replace and nonempty append with accepted not verified results`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("replace", "android_input_text", """{"text":"","mode":"replace"}"""),
            function("append", "android_input_text", """{"text":"hello","mode":"append"}""")), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 1) { h.input.typeText("") }
        coVerify(exactly = 1) { h.input.appendText("hello") }
        coVerify { h.client.request(match { it.size == 2 && it.all { result -> result.jsonObject["output"]?.jsonPrimitive?.content?.contains("verify the final field") == true } }, "r1") }
    }

    @Test fun `failed replace acknowledgement prevents subsequent append and fails once`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.input.typeText(any()) } returns InputResult.failure("clear not accepted")
        h.respond(functionResponse("r1", function("replace", "android_input_text", """{"text":"new","mode":"replace"}"""),
            function("append", "android_input_text", """{"text":"later","mode":"append"}""")))
        assertFalse(h.run().success)
        coVerify(exactly = 0) { h.input.appendText(any()) }
        assertEquals(1, h.completions.size)
    }

    @Test fun `humanized swipe maps source pixels and requested duration before a fresh capture`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2", gesture(operation = "swipe", endX = 30, endY = 40)), finalResponse())
        assertTrue(h.run().success)
        verify { h.swipes.generatePath(15, 30, 45, 60, 1080, 1920) }
        coVerify { h.device.swipe(match { it.first().x == 15 && it.last().x == 45 }, 400) }
        coVerify(exactly = 2) { h.screenshots.capture() }
    }

    @Test fun `long press maps screenshot coordinates without losing its duration`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2", gesture()), finalResponse())
        assertTrue(h.run().success)
        coVerify(exactly = 1) { h.device.longPress(15, 30, 400) }
        coVerify(exactly = 2) { h.screenshots.capture() }
    }

    @Test fun `sensitive gesture waits for real confirmation and rejected reply prevents its side effect`() = runTest {
        val h = RunnerHarness(this)
        h.respond(screenshotRequest(), functionResponse("r2", gesture(confirmation = "Send message")), finalResponse())
        h.begin()
        val pending = requireNotNull(h.coordinator.snapshot.value.pendingInteraction)
        assertEquals(InteractionKind.CONFIRM, pending.kind)
        assertEquals(RunPhase.WAITING_INTERACTION, h.coordinator.snapshot.value.phase)
        assertFalse(h.coordinator.reply("stale-run", pending.id, InteractionReply.Accepted(true)))
        assertTrue(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Accepted(false)))
        assertTrue(h.settle().success)
        coVerify(exactly = 0) { h.device.longPress(any(), any(), any()) }
        coVerify { h.client.request(match { it.singleOutput()?.contains("declined") == true }, "r2") }
    }

    @Test fun `takeover exposes shared waiting state and resumes only its matching interaction`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("takeover", "android_take_over", """{"message":"Unlock"}""")), finalResponse())
        h.begin()
        val pending = requireNotNull(h.coordinator.snapshot.value.pendingInteraction)
        assertEquals(InteractionKind.TAKEOVER, pending.kind)
        assertEquals("Unlock", pending.prompt)
        assertFalse(h.coordinator.reply(pending.runId, pending.id + 1, InteractionReply.Accepted(true)))
        assertFalse(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Text("wrong reply type")))
        assertTrue(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Accepted(true)))
        assertTrue(h.settle().success)
        coVerify { h.client.request(match { it.singleOutput() == "User completed the manual step" }, "r1") }
    }

    @Test fun `choice uses actual selected option and cancellation returns no invented selection`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("choice", "android_interact", """{"question":"Choose","options":["A","B"]}""")), finalResponse())
        h.begin()
        var pending = requireNotNull(h.coordinator.snapshot.value.pendingInteraction)
        assertEquals(InteractionKind.CHOICE, pending.kind)
        assertFalse(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Choice(99)))
        assertTrue(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Choice(1)))
        assertTrue(h.settle().success)
        coVerify { h.client.request(match { it.singleOutput() == "User selected: B" }, "r1") }
        h.respond(functionResponse("r3", function("choice2", "android_interact", """{"question":"Choose","options":["A","B"]}""")), finalResponse("r4"))
        h.begin(forceNew = true)
        pending = requireNotNull(h.coordinator.snapshot.value.pendingInteraction)
        assertTrue(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Cancelled))
        assertTrue(h.settle().success)
        coVerify { h.client.request(match { it.singleOutput() == "User cancelled the interaction" }, "r3") }
    }

    @Test fun `chat reply answers pending question instead of entering the unrelated instruction queue`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("ask", "android_ask_user", """{"question":"Question"}""")), finalResponse())
        h.begin()
        val pending = requireNotNull(h.coordinator.snapshot.value.pendingInteraction)
        assertEquals(InteractionKind.TEXT, pending.kind)
        assertFalse(h.coordinator.pauseForDraft())
        assertTrue(h.coordinator.submit("actual answer"))
        assertTrue(h.settle().success)
        assertEquals(listOf("task", "Question", "actual answer", "done"), h.coordinator.messages.value.map { it.text })
        coVerify { h.client.request(match { it.singleOrNull()?.jsonObject?.get("call_id")?.jsonPrimitive?.content == "ask" &&
            it.singleOutput() == "User replied: actual answer" }, "r1") }
    }

    @Test fun `stop cancels a pending interaction and stale reply cannot continue the old task`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("ask", "android_ask_user", """{"question":"Question"}""")))
        h.begin()
        val pending = requireNotNull(h.coordinator.snapshot.value.pendingInteraction)
        assertTrue(h.coordinator.stop())
        assertFalse(h.settle().success)
        assertNull(h.coordinator.snapshot.value.pendingInteraction)
        assertFalse(h.coordinator.reply(pending.runId, pending.id, InteractionReply.Text("late")))
        assertEquals(1, h.completions.size)
        coVerify(exactly = 1) { h.client.request(any(), any()) }
    }

    @Test fun `custom wait exposes skip control and invalidates old semantic state`() = runTest {
        val service = mockk<PhoneAccessibilityService>(relaxed = true)
        coEvery { service.observe() } returns UiSnapshot("s1", "tree")
        val h = RunnerHarness(this, service = service)
        h.respond(functionResponse("r1", function("wait", "android_wait", """{"duration_ms":5000,"reason":"loading"}""")), finalResponse())
        h.begin()
        val wait = requireNotNull(h.coordinator.snapshot.value.activeWait)
        assertEquals("loading", wait.reason)
        assertFalse(h.coordinator.skipWait("wrong-run", wait.id))
        assertTrue(h.coordinator.skipWait(wait.runId, wait.id))
        assertTrue(h.settle().success)
        coVerify { service.invalidateObservation() }
        coVerify { h.client.request(match { it.singleOutput()?.contains("finished early") == true }, "r1") }
    }

    @Test fun `native wait shares coordinator skip control without a second wait engine`() = runTest {
        val h = RunnerHarness(this)
        h.respond(nativeResponse("r1", "wait", """{"type":"wait"}"""), finalResponse())
        h.begin()
        val wait = requireNotNull(h.coordinator.snapshot.value.activeWait)
        assertTrue(h.coordinator.skipWait(wait.runId, wait.id))
        assertTrue(h.settle().success)
        coVerify(exactly = 1) { h.screenshots.capture() }
        coVerify(exactly = 0) { h.device.pressKey(any()) }
    }

    @Test fun `pause at a request boundary blocks the function side effect until resume`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.client.request(any(), null) } coAnswers {
            h.coordinator.pauseUser()
            functionResponse("r1", function("home", "android_system_key", """{"key":"HOME"}"""))
        }
        coEvery { h.client.request(any(), "r1") } returns finalResponse()
        h.begin()
        assertEquals(RunPhase.PAUSED, h.coordinator.snapshot.value.phase)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        h.coordinator.resumeUser()
        assertTrue(h.settle().success)
        coVerify(exactly = 1) { h.device.pressKey(3) }
    }
}
