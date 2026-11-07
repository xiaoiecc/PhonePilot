package com.kevinluo.autoglm.history

import android.content.Context
import com.kevinluo.autoglm.core.ConversationSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConversationHistoryTest {
    @Test fun `failed tool trace persists even when no completed model step exists`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("history-tool-trace").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        val manager = HistoryManager::class.java.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }.newInstance(context)
        val task = manager.startTask("goal", "chat")
        manager.recordMessage(HistoryMessageRole.USER, "goal")
        val call = HistoryToolCall("call-1", "run-1", 2, "computer", "click at 10,20", 10, 12,
            HistoryToolStatus.FAILED, "input rejected", listOf(com.kevinluo.autoglm.action.AgentAction.Tap(10, 20)))
        manager.recordToolCall(call)
        manager.completeTask(false, "input rejected")
        val restored = manager.getTask(task.id)!!
        assertTrue(restored.steps.isEmpty())
        assertEquals(listOf(call), restored.toolCalls)
        assertEquals("goal", restored.messages.single().text)
    }
    @Test fun `JSON null history fields stay absent while literal null text remains real text`() {
        val raw = """{"id":"legacy-null","conversationId":null,"taskDescription":"goal","startTime":10,
            "success":false,"completionMessage":null,"previousResponseId":null,"steps":[{
            "stepNumber":1,"timestamp":11,"thinking":"","actionDescription":"observe","success":true,
            "screenshotPath":null,"annotatedScreenshotPath":null,"message":null}]}"""
        val task = HistoryManager.taskHistoryFromJson(raw)
        assertEquals("legacy-null", task.conversationId)
        assertNull(task.completionMessage)
        assertNull(task.previousResponseId)
        assertNull(task.steps.single().screenshotPath)
        assertNull(task.steps.single().annotatedScreenshotPath)
        assertNull(task.steps.single().message)
        assertEquals("null", HistoryManager.taskHistoryFromJson(raw.replace("\"message\":null", "\"message\":\"null\"")).steps.single().message)
    }
    @Test fun `list groups runs into conversations and retains the original title`() {
        val first = TaskHistory("run-1", "first goal", 10, "chat-1", messages = mutableListOf(
            HistoryMessage(HistoryMessageRole.USER, "first goal", 10)))
        val followup = TaskHistory("run-2", "follow up", 20, "chat-1", messages = mutableListOf(
            HistoryMessage(HistoryMessageRole.USER, "follow up", 20)))
        val other = TaskHistory("other", "other goal", 15, "chat-2")
        val summaries = ConversationHistory.summaries(listOf(followup, other, first))
        assertEquals(listOf("chat-1", "chat-2"), summaries.map { it.conversationId })
        assertEquals("first goal", summaries.first().title)
        assertEquals(2, summaries.first().messages.size)
    }

    @Test fun `same title conversations stay separate and sort by last activity not creation`() {
        val old = TaskHistory("old", "same title", 10, "chat-old", endTime = 50)
        val newer = TaskHistory("newer", "same title", 30, "chat-new", endTime = 40)
        val summaries = ConversationHistory.summaries(listOf(newer, old), nowMs = 60)
        assertEquals(listOf("chat-old", "chat-new"), summaries.map { it.conversationId })
        assertEquals(listOf("same title", "same title"), summaries.map { it.title })
        assertEquals(listOf(50L, 40L), summaries.map { it.lastActivityTime })
    }

    @Test fun `summary duration sums execution time and detail retains every real timestamp and screenshot`() {
        fun step(time: Long, path: String) = HistoryStep(1, time, "thinking", null, "action", path, "$path.annotated", true)
        val first = TaskHistory("first", "original title", 100, "chat", endTime = 200,
            steps = mutableListOf(step(150, "first.webp")),
            messages = mutableListOf(HistoryMessage(HistoryMessageRole.USER, "first", 100)))
        val second = TaskHistory("second", "followup title", 1000, "chat", endTime = 1050, success = true,
            steps = mutableListOf(step(1020, "second.png")),
            messages = mutableListOf(HistoryMessage(HistoryMessageRole.USER, "second", 1000)))
        val summary = ConversationHistory.summaries(listOf(second, first), nowMs = 2000).single()
        assertEquals(150L, summary.executionDurationMs)
        assertEquals(2, summary.stepCount)
        assertEquals(100L, summary.startTime)
        assertEquals(1050L, summary.lastActivityTime)
        assertEquals("original title", summary.title)
        val detail = ConversationHistory.detail(listOf(second, first), "chat")!!
        assertEquals(100L, detail.startTime)
        assertEquals(1050L, detail.endTime)
        assertEquals(listOf(150L, 1020L), detail.steps.map { it.timestamp })
        assertEquals(listOf("first.webp", "second.png"), detail.steps.map { it.screenshotPath })
        assertEquals(listOf("first.webp.annotated", "second.png.annotated"), detail.steps.map { it.annotatedScreenshotPath })
        assertEquals(listOf(1, 2), detail.steps.map { it.stepNumber })
        assertEquals(listOf("first", "second"), detail.messages.map { it.text })
        assertEquals(1, first.steps.single().stepNumber)
        assertEquals(1, second.steps.single().stepNumber)
    }

    @Test fun `running and invalid legacy durations never add negative elapsed or idle gaps`() {
        val completed = TaskHistory("done", "goal", 10, "chat", endTime = 20)
        val running = TaskHistory("running", "follow", 100, "chat")
        val invalid = TaskHistory("bad-clock", "clock", 200, "other", endTime = 190)
        val summaries = ConversationHistory.summaries(listOf(running, completed, invalid), nowMs = 150)
        assertEquals(60L, summaries.single { it.conversationId == "chat" }.executionDurationMs)
        assertEquals(0L, summaries.single { it.conversationId == "other" }.executionDurationMs)
        assertNull(ConversationHistory.detail(listOf(running, completed), "chat")!!.endTime)
        assertNull(ConversationHistory.detail(listOf(completed), "missing"))
    }

    @Test fun `latest cancelled run cannot revive an older response cursor`() {
        val old = TaskHistory("old", "old goal", 10, "chat", previousResponseId = "response-old")
        val stopped = TaskHistory("stopped", "stop", 20, "chat", previousResponseId = null)
        assertNull(ConversationHistory.restore(listOf(stopped, old), "chat")!!.session.previousResponseId)
        assertNull(ConversationHistory.restore(listOf(stopped, old), "missing"))
    }

    @Test fun `equal timestamp runs use persisted newest-first index order`() {
        val old = TaskHistory("z-old", "old goal", 10, "chat", previousResponseId = "response-old")
        val latest = TaskHistory("a-new", "new goal", 10, "chat", previousResponseId = "response-new")
        assertEquals("response-new", ConversationHistory.restore(listOf(latest, old), "chat")!!.session.previousResponseId)
        assertEquals("old goal", ConversationHistory.summaries(listOf(latest, old), 20).single().title)
        assertEquals("response-new", ConversationHistory.detail(listOf(latest, old), "chat")!!.previousResponseId)
    }

    @Test fun `legacy runs without message arrays remain readable without inventing a cursor`() {
        val legacy = HistoryManager.taskHistoryFromJson("""{"id":"legacy","taskDescription":"goal","startTime":10,
            "endTime":20,"success":true,"completionMessage":"done","steps":[]}""")
        val restored = ConversationHistory.restore(listOf(legacy), "legacy")!!
        assertEquals(listOf("goal", "done"), restored.messages.map { it.text })
        assertNull(restored.session.previousResponseId)
        assertFalse(restored.session.hasInputUsage)
    }

    @Test fun `session metadata and messages round trip and deletion covers every run`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("conversation-history").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        val manager = HistoryManager::class.java.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }.newInstance(context)
        val session = ConversationSnapshot("chat", "response-1", 1500, 210, 1000, true, true)
        val first = manager.startTask("goal", session.id)
        manager.recordMessage(HistoryMessageRole.USER, "goal")
        manager.recordMessage(HistoryMessageRole.AGENT, "done")
        manager.updateConversationSession(session)
        manager.completeTask(true, "done")
        val onDisk = manager.getTask(first.id)!!
        assertEquals("response-1", onDisk.previousResponseId)
        assertEquals(1500L, onDisk.totalInputTokens)
        assertEquals(210L, onDisk.totalOutputTokens)
        assertEquals(1000L, onDisk.totalCachedTokens)
        assertTrue(onDisk.hasInputUsage)
        assertTrue(onDisk.hasOutputUsage)

        manager.startTask("follow up", "chat")
        manager.recordMessage(HistoryMessageRole.USER, "follow up")
        manager.updateConversationSession(session.copy(previousResponseId = null))
        manager.completeTask(false, "cancelled")
        val restored = manager.loadConversation("chat")!!
        assertEquals(listOf("goal", "done", "follow up"), restored.messages.map { it.text })
        assertNull(restored.session.previousResponseId)
        assertEquals(1500L, restored.session.totalInputTokens)
        val separate = manager.startTask("goal", "separate-chat")
        manager.recordMessage(HistoryMessageRole.USER, "goal")
        manager.completeTask(true, "done")
        assertEquals(2, ConversationHistory.summaries(manager.historyList.value).size)
        manager.deleteConversations(setOf("chat"))
        assertEquals(listOf("separate-chat"), manager.historyList.value.map { it.conversationId })
        assertNull(manager.getTask(first.id))
        assertNotNull(manager.getTask(separate.id))
        assertNull(manager.loadConversation("chat"))
    }
}
