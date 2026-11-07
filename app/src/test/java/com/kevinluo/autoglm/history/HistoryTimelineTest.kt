package com.kevinluo.autoglm.history

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryTimelineTest {
    private fun call(run: String = "run", step: Int = 1, time: Long = 20, status: HistoryToolStatus = HistoryToolStatus.SUCCEEDED) =
        HistoryToolCall("call-$run-$step", run, step, "android_ui", "{\"operation\":\"click\",\"node_id\":\"n3\"}", time, time + 75,
            status, "Focused the selected field", beforeScreenshotPath = "before.webp", afterScreenshotPath = "after.webp",
            annotatedScreenshotPath = "tap.webp")
    private fun step(number: Int = 1, time: Long = 20) = HistoryStep(number, time, "legacy reasoning", null,
        "Tap", "old.webp", "old-annotated.webp", true)

    @Test fun `failure before first tool is an explicit diagnostic not an invented assistant message`() {
        val user = HistoryMessage(HistoryMessageRole.USER, "Open calculator", 10)
        val history = TaskHistory(id = "history", taskDescription = user.text, startTime = 10, endTime = 20,
            completionMessage = "HTTP 401: invalid API key", messages = mutableListOf(user))
        val entries = HistoryTimeline.build(listOf(history))
        assertEquals(listOf(user), entries.filterIsInstance<HistoryTimeline.Entry.Message>().map { it.message })
        val outcome = entries.filterIsInstance<HistoryTimeline.Entry.Outcome>().single()
        assertEquals(20L, outcome.timestamp)
        assertEquals("HTTP 401: invalid API key", outcome.reason)
        assertTrue(HistoryTimeline.text(outcome).contains("HTTP 401"))
        assertTrue(HistoryTimeline.images(outcome).isEmpty())
    }

    @Test fun `cancelled run without tools retains its termination reason once`() {
        val history = TaskHistory(id = "history", taskDescription = "task", startTime = 10, endTime = 20,
            completionMessage = "Stopped by user")
        val entries = HistoryTimeline.build(listOf(history))
        assertEquals("Stopped by user", entries.filterIsInstance<HistoryTimeline.Entry.Outcome>().single().reason)
        assertEquals(1, entries.filterIsInstance<HistoryTimeline.Entry.Message>().size)
    }

    @Test fun `same failure in tool trace is not repeated but distinct termination reason is retained`() {
        val history = TaskHistory(id = "history", taskDescription = "task", startTime = 10, endTime = 100,
            completionMessage = "HTTP 401", toolCalls = mutableListOf(call(status = HistoryToolStatus.FAILED).copy(result = "ERROR: HTTP 401")))
        assertTrue(HistoryTimeline.build(listOf(history)).none { it is HistoryTimeline.Entry.Outcome })
        val changed = history.copy(completionMessage = "Disconnected during cleanup")
        assertEquals("Disconnected during cleanup", HistoryTimeline.build(listOf(changed))
            .filterIsInstance<HistoryTimeline.Entry.Outcome>().single().reason)
    }

    @Test fun `actual successful final assistant message is kept without extra outcome`() {
        val final = HistoryMessage(HistoryMessageRole.AGENT, "Done", 30)
        val history = TaskHistory(id = "history", taskDescription = "task", success = true, completionMessage = "Done",
            messages = mutableListOf(final))
        val entries = HistoryTimeline.build(listOf(history))
        assertEquals(listOf(final), entries.filterIsInstance<HistoryTimeline.Entry.Message>().map { it.message })
        assertTrue(entries.none { it is HistoryTimeline.Entry.Outcome })
    }

    @Test fun `messages and tools form one chronological conversation including later prompts`() {
        val first = TaskHistory(id = "run", taskDescription = "first", startTime = 10,
            messages = mutableListOf(HistoryMessage(HistoryMessageRole.USER, "first", 10),
                HistoryMessage(HistoryMessageRole.AGENT, "I will open it", 15),
                HistoryMessage(HistoryMessageRole.AGENT, "opened", 30)), toolCalls = mutableListOf(call().copy(finishedAt = 25)))
        val next = TaskHistory(id = "follow", taskDescription = "now type", startTime = 40,
            messages = mutableListOf(HistoryMessage(HistoryMessageRole.USER, "now type", 40)))
        val timeline = HistoryTimeline.build(listOf(first, next))
        assertEquals(listOf(10L, 15L, 25L, 30L, 40L), timeline.map { it.timestamp })
        assertEquals(listOf("first", "I will open it", "opened", "now type"),
            timeline.filterIsInstance<HistoryTimeline.Entry.Message>().map { it.message.text })
    }

    @Test fun `trace suppression is scoped to original run and turn not globally repeated step numbers`() {
        val first = TaskHistory(id = "run", taskDescription = "first", startTime = 1,
            steps = mutableListOf(step(), step(2, 25)), toolCalls = mutableListOf(call()))
        val second = TaskHistory(id = "other", taskDescription = "follow", startTime = 30,
            steps = mutableListOf(step(1, 40)))
        val entries = HistoryTimeline.build(listOf(first, second))
        val legacy = entries.filterIsInstance<HistoryTimeline.Entry.LegacyStep>()
        assertEquals(listOf("run" to 2, "other" to 1), legacy.map { it.historyRunId to it.step.stepNumber })
        assertEquals(1, entries.filterIsInstance<HistoryTimeline.Entry.Tool>().size)
        assertEquals(2, first.steps.size)
    }

    @Test fun `failed and cancelled calls survive even with no legacy step or completion message`() {
        val failed = call(status = HistoryToolStatus.FAILED).copy(result = "IME rejected focus")
        val cancelled = call(step = 2, time = 50, status = HistoryToolStatus.CANCELLED).copy(result = "Stopped before screenshot")
        val task = TaskHistory(id = "run", taskDescription = "task", toolCalls = mutableListOf(failed, cancelled))
        val entries = HistoryTimeline.build(listOf(task)).filterIsInstance<HistoryTimeline.Entry.Tool>()
        assertEquals(listOf(failed, cancelled), entries.map { it.call })
        assertTrue(HistoryTimeline.text(entries.first()).contains("失败"))
        assertTrue(HistoryTimeline.text(entries.last()).contains("Stopped before screenshot"))
    }

    @Test fun `screenshot variants preserve exact origin with honest before annotation after labels`() {
        val images = HistoryTimeline.images(HistoryTimeline.Entry.Tool(call()))
        assertEquals(listOf(HistoryTimeline.ImageKind.BEFORE, HistoryTimeline.ImageKind.ANNOTATED, HistoryTimeline.ImageKind.AFTER), images.map { it.kind })
        assertEquals(listOf("before.webp", "tap.webp", "after.webp"), images.map { it.path })
        val onlyAfter = call().copy(beforeScreenshotPath = null, annotatedScreenshotPath = null)
        assertEquals(listOf(HistoryTimeline.Image(HistoryTimeline.ImageKind.AFTER, "after.webp")),
            HistoryTimeline.images(HistoryTimeline.Entry.Tool(onlyAfter)))
    }

    @Test fun `legacy fallback does not invent an assistant failure reply and preserves diagnostic step`() {
        val task = TaskHistory(id = "old", taskDescription = "legacy prompt", startTime = 10,
            endTime = 40, completionMessage = "Could not finish", steps = mutableListOf(step().copy(success = false, message = "Could not finish")))
        val entries = HistoryTimeline.build(listOf(task))
        assertEquals(listOf("legacy prompt"), entries.filterIsInstance<HistoryTimeline.Entry.Message>().map { it.message.text })
        val old = entries.filterIsInstance<HistoryTimeline.Entry.LegacyStep>().single()
        assertEquals(listOf("old.webp", "old-annotated.webp"), HistoryTimeline.images(old).map { it.path })
        assertEquals("Could not finish", old.step.message)
        val successful = task.copy(success = true, completionMessage = "Finished")
        assertEquals(listOf("legacy prompt", "Finished"), HistoryTimeline.build(listOf(successful))
            .filterIsInstance<HistoryTimeline.Entry.Message>().map { it.message.text })
    }

    @Test fun `tool parent association suppresses legacy turn even when history UUID differs from coordinator UUID`() {
        val trace = call(run = "coordinator-uuid")
        val history = TaskHistory(id = "history-record-uuid", taskDescription = "task", startTime = 10,
            steps = mutableListOf(step()), toolCalls = mutableListOf(trace))
        val entries = HistoryTimeline.build(listOf(history))
        assertTrue(entries.none { it is HistoryTimeline.Entry.LegacyStep })
        assertEquals(trace, entries.filterIsInstance<HistoryTimeline.Entry.Tool>().single().call)
        assertEquals("history-record-uuid", entries.filterIsInstance<HistoryTimeline.Entry.Message>().single().historyRunId)
    }

    @Test fun `long tool output has a concise summary while full export details remain intact`() {
        val trace = call(status = HistoryToolStatus.FAILED).copy(result = "AX failed: " + "节点".repeat(6000), detailsTruncated = true)
        val summary = HistoryTimeline.summary(trace)
        assertTrue(summary.length < 300)
        assertTrue(summary.contains("失败"))
        assertTrue(summary.contains("AX failed:"))
        assertTrue(summary.contains("记录内容已截断"))
        assertTrue(HistoryTimeline.text(HistoryTimeline.Entry.Tool(trace)).contains(trace.result))
    }

    @Test fun `detail merging retains every call original identity timestamps and does not mutate storage`() {
        val a = TaskHistory(id = "a", conversationId = "chat", taskDescription = "first", startTime = 10,
            toolCalls = mutableListOf(call("a", time = 20)))
        val b = TaskHistory(id = "b", conversationId = "chat", taskDescription = "second", startTime = 40,
            toolCalls = mutableListOf(call("b", time = 50)))
        val detail = requireNotNull(ConversationHistory.detail(listOf(b, a), "chat"))
        assertEquals(listOf("a", "b"), detail.toolCalls.map { it.runId })
        assertEquals(listOf(20L, 50L), detail.toolCalls.map { it.startedAt })
        assertEquals(listOf(1, 1), detail.toolCalls.map { it.stepNumber })
        assertEquals(1, a.toolCalls.size)
        assertEquals(1, b.toolCalls.size)
    }

    @Test fun `shared detail text includes parameters results timing status identity and truncation`() {
        val trace = call().copy(detailsTruncated = true)
        val text = HistoryTimeline.text(HistoryTimeline.Entry.Tool(trace))
        listOf(trace.name, trace.callId, trace.runId, trace.arguments, trace.result, "75 ms", "成功", "记录内容已截断")
            .forEach { assertTrue(text.contains(it), "Missing detail: $it") }
        assertTrue(HistoryTimeline.text(HistoryTimeline.Entry.Tool(trace.copy(finishedAt = 0))).contains("0 ms"))
    }

    @Test fun `same time order is stable and no historical tool is turned into an executable action`() {
        val one = call().copy(callId = "one", finishedAt = 20)
        val two = call().copy(callId = "two", finishedAt = 20)
        val run = TaskHistory(id = "run", taskDescription = "prompt", startTime = 20, toolCalls = mutableListOf(one, two))
        val entries = HistoryTimeline.build(listOf(run))
        assertTrue(entries.first() is HistoryTimeline.Entry.Message)
        assertEquals(listOf("one", "two"), entries.filterIsInstance<HistoryTimeline.Entry.Tool>().map { it.call.callId })
    }

    @Test fun `interaction question and user reply precede tool result and final assistant message`() {
        val question = HistoryMessage(HistoryMessageRole.AGENT, "Allow this operation?", 12)
        val reply = HistoryMessage(HistoryMessageRole.USER, "Yes", 15)
        val final = HistoryMessage(HistoryMessageRole.AGENT, "Done", 17)
        val trace = call(time = 10).copy(finishedAt = 16)
        val history = TaskHistory(id = "history", taskDescription = "task", startTime = 1,
            messages = mutableListOf(question, reply, final), toolCalls = mutableListOf(trace))
        val timeline = HistoryTimeline.build(listOf(history))
        assertEquals(listOf(12L, 15L, 16L, 17L), timeline.map { it.timestamp })
        assertEquals(listOf(question, reply, final), timeline.filterIsInstance<HistoryTimeline.Entry.Message>().map { it.message })
        assertEquals(trace, (timeline[2] as HistoryTimeline.Entry.Tool).call)
        val text = HistoryTimeline.text(timeline[2])
        assertTrue(text.contains("开始 "))
        assertTrue(text.contains("结束 "))
        assertTrue(text.contains("00.010"))
        assertTrue(text.contains("00.016"))
    }
}
