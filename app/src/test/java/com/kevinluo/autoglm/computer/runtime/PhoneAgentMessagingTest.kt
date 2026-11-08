package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.computer.protocol.*
import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.history.HistoryMessageRole
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAgentMessagingTest {
    @Test fun `clearing a draft releases only draft pause and never makes an extra api call`() = runTest {
        val h = RunnerHarness(this)
        val response = CompletableDeferred<ComputerResponse>()
        coEvery { h.client.request(any(), any()) } coAnswers { response.await() }
        h.begin()
        h.coordinator.pauseForDraft()
        h.coordinator.pauseUser()
        h.coordinator.onEditorBlur(hasDraft = true)
        assertEquals(setOf(PauseReason.USER, PauseReason.DRAFT), h.coordinator.snapshot.value.pauseReasons)
        h.coordinator.onEditorBlur(hasDraft = false)
        assertEquals(setOf(PauseReason.USER), h.coordinator.snapshot.value.pauseReasons)
        assertEquals(RunPhase.PAUSED, h.coordinator.snapshot.value.phase)
        h.coordinator.resumeUser()
        response.complete(finalResponse())
        assertTrue(h.settle().success)
        coVerify(exactly = 1) { h.client.request(any(), any()) }
    }

    @Test fun `message arriving with a response cancels all old calls before execution`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.client.request(any(), null) } coAnswers {
            assertTrue(h.coordinator.submit("use new direction"))
            functionResponse("r1", function("a", "android_system_key", """{"key":"HOME"}"""),
                function("b", "android_system_key", """{"key":"BACK"}"""))
        }
        coEvery { h.client.request(any(), "r1") } returns finalResponse()
        assertTrue(h.run().success)
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        coVerify { h.client.request(match { input ->
            input.map { it.jsonObject["call_id"]?.jsonPrimitive?.content } == listOf("a", "b", null) &&
                input.take(2).all { it.jsonObject["output"]!!.jsonPrimitive.content.startsWith("Not executed:") }
        }, "r1") }
    }

    @Test fun `message during a batch stops remainder and supplies every output exactly once`() = runTest {
        val h = RunnerHarness(this)
        h.respond(functionResponse("r1", function("a", "android_system_key", """{"key":"HOME"}"""),
            function("b", "android_system_key", """{"key":"BACK"}""")), finalResponse())
        coEvery { h.device.pressKey(3) } coAnswers { assertTrue(h.coordinator.submit("change direction")); CommandResult(0) }
        assertTrue(h.run().success)
        coVerify(exactly = 1) { h.device.pressKey(3) }
        coVerify(exactly = 0) { h.device.pressKey(4) }
        coVerify { h.client.request(match { input ->
            input.mapNotNull { it.jsonObject["call_id"]?.jsonPrimitive?.content } == listOf("a", "b") &&
                input[1].jsonObject["output"]!!.jsonPrimitive.content.startsWith("Not executed:") &&
                input.last().jsonObject["role"]?.jsonPrimitive?.content == "user"
        }, "r1") }
    }

    @Test fun `a queued message continues past a stale final answer`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.client.request(any(), null) } coAnswers {
            assertTrue(h.coordinator.submit("follow up now"))
            finalResponse("r1", "old answer")
        }
        coEvery { h.client.request(any(), "r1") } returns finalResponse("r2", "new answer")
        assertEquals("new answer", h.run().message)
        assertEquals(listOf("task", "follow up now", "old answer", "new answer"), h.coordinator.messages.value.map { it.text })
        coVerify(exactly = 1) { h.history.recordMessage(HistoryMessageRole.USER, "follow up now") }
    }

    @Test fun `message accepted during final step persistence survives step limit without another request`() = runTest {
        val h = RunnerHarness(this, maxSteps = 1)
        val recording = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { h.history.recordStep(any(), any(), any(), any(), any(), any()) } coAnswers {
            recording.complete(Unit)
            release.await()
        }
        h.respond(functionResponse("r1", function("note", "android_note", """{"message":"note"}""")))
        h.begin()
        assertTrue(recording.isCompleted)
        assertTrue(h.coordinator.submit("late accepted instruction"))
        assertEquals(1, h.coordinator.messages.value.count { it.text == "late accepted instruction" })
        release.complete(Unit)
        assertFalse(h.settle().success)
        coVerify(exactly = 1) { h.history.recordMessage(HistoryMessageRole.USER, "late accepted instruction") }
        coVerify(exactly = 1) { h.client.request(any(), any()) }
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        assertEquals(1, h.completions.size)
    }

    @Test fun `accepted message survives network failure without retry or duplicate publication`() = runTest {
        val h = RunnerHarness(this)
        coEvery { h.client.request(any(), any()) } coAnswers {
            assertTrue(h.coordinator.submit("accepted before failure"))
            throw java.io.IOException("connection lost")
        }
        assertFalse(h.run().success)
        assertEquals(1, h.coordinator.messages.value.count { it.text == "accepted before failure" })
        coVerify(exactly = 1) { h.history.recordMessage(HistoryMessageRole.USER, "accepted before failure") }
        coVerify(exactly = 1) { h.client.request(any(), any()) }
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        assertEquals(1, h.completions.size)
    }

    @Test fun `accepted message survives stop while network request is suspended without replay`() = runTest {
        val h = RunnerHarness(this)
        val request = CompletableDeferred<ComputerResponse>()
        coEvery { h.client.request(any(), any()) } coAnswers { request.await() }
        h.begin()
        assertTrue(h.coordinator.submit("accepted before stop"))
        h.coordinator.stop()
        assertFalse(h.settle().success)
        assertEquals(1, h.coordinator.messages.value.count { it.text == "accepted before stop" })
        coVerify(exactly = 1) { h.history.recordMessage(HistoryMessageRole.USER, "accepted before stop") }
        coVerify(exactly = 1) { h.client.request(any(), any()) }
        coVerify(exactly = 0) { h.device.pressKey(any()) }
        assertEquals(1, h.completions.size)
    }

    @Test fun `cancelled transport with pending steering retains already executed function outputs`() = runTest {
        val h = RunnerHarness(this)
        val previous = mutableListOf<String?>()
        val requests = mutableListOf<JsonArray>()
        coEvery { h.client.request(any(), any()) } coAnswers {
            previous += secondArg<String?>()
            requests += firstArg<JsonArray>()
            when (requests.size) {
                1 -> functionResponse("r0", function("key", "android_system_key", """{"key":"HOME"}"""))
                2 -> {
                    assertTrue(h.coordinator.submit("redirect now"))
                    throw CancellationException("transport cancelled")
                }
                else -> finalResponse("r1", "redirected")
            }
        }
        assertTrue(h.run().success)
        assertEquals(listOf(null, "r0", "r0"), previous)
        assertEquals(requests[1].toList(), requests[2].dropLast(1))
        assertEquals("key", requests[2].first().jsonObject["call_id"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { h.device.pressKey(3) }
    }

    @Test fun `steering after cancelled transport preserves native screenshot output without recapture`() = runTest {
        val h = RunnerHarness(this)
        val requests = mutableListOf<JsonArray>()
        coEvery { h.client.request(any(), any()) } coAnswers {
            requests += firstArg<JsonArray>()
            when (requests.size) {
                1 -> nativeResponse("r0", "screen", """{"type":"screenshot"}""")
                2 -> { assertTrue(h.coordinator.submit("change task")); throw CancellationException("transport cancelled") }
                else -> finalResponse()
            }
        }
        assertTrue(h.run().success)
        assertEquals(requests[1].toList(), requests[2].dropLast(1))
        assertEquals("computer_call_output", requests[2].first().jsonObject["type"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { h.screenshots.capture() }
    }

    @Test fun `stop abandons incomplete cursor but permits safe chat continuation without replay`() = runTest {
        val h = RunnerHarness(this)
        var requests = 0
        coEvery { h.client.request(any(), any()) } coAnswers {
            when (++requests) {
                1 -> functionResponse("r0", function("key", "android_system_key", """{"key":"HOME"}"""))
                else -> { h.coordinator.stop(); throw CancellationException("cancelled") }
            }
        }
        assertFalse(h.run().success)
        assertNull(h.runner.snapshot().previousResponseId)
        assertTrue(h.runner.canContinue())
        assertEquals(2, requests)
        coVerify(exactly = 1) { h.device.pressKey(3) }
        assertEquals(1, h.completions.size)
        val conversationId = h.runner.snapshot().id
        h.respond(finalResponse())
        assertTrue(h.run("Continue the conversation").success)
        assertEquals(conversationId, h.runner.snapshot().id)
        coVerify { h.client.request(match { !it.toString().contains("function_call_output") &&
            it.toString().contains("Earlier visible conversation") }, null) }
        coVerify(exactly = 1) { h.device.pressKey(3) }
    }

    @Test fun `one running message carries all images in selection order`() = runTest {
        val h = RunnerHarness(this)
        val images = listOf(ChatImage("first.webp", "image/webp"), ChatImage("second.webp", "image/webp"))
        coEvery { h.client.request(any(), null) } coAnswers {
            assertTrue(h.coordinator.submit("Compare new images", images = images))
            finalResponse("r1", "old answer")
        }
        coEvery { h.client.request(any(), "r1") } returns finalResponse("r2", "new answer")
        assertTrue(h.run().success)
        coVerify { h.client.request(match {
            val wire = it.toString()
            wire.contains("Compare new images") && wire.contains("attachment-first.webp") &&
                wire.contains("attachment-second.webp") &&
                wire.indexOf("attachment-first.webp") < wire.indexOf("attachment-second.webp")
        }, "r1") }
        assertEquals(images, h.coordinator.messages.value.first { it.text == "Compare new images" }.images)
    }

    @Test fun `history finalization rejects new input and releases one completion afterwards`() = runTest {
        val h = RunnerHarness(this)
        val finalizing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { h.history.completeTask(any(), any()) } coAnswers { finalizing.complete(Unit); release.await() }
        h.respond(finalResponse("r1"))
        h.begin()
        assertTrue(finalizing.isCompleted)
        assertTrue(h.coordinator.isBusy)
        assertFalse(h.coordinator.submit("must stay in editor"))
        assertFalse(h.runner.enqueueMessage(ChatInput("cannot accept while saving")))
        release.complete(Unit)
        assertTrue(h.settle().success)
        assertEquals(1, h.completions.size)
        h.respond(finalResponse("r2"))
        assertTrue(h.run("next message").success)
        assertEquals(2, h.completions.size)
    }
}
