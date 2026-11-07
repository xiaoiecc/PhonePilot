package com.kevinluo.autoglm.task

import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.history.HistoryMessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskExecutionManagerConversationTest {
    @Test
    fun `history keeps user and agent messages within a conversation`() {
        val history = HistoryManager.taskHistoryFromJson("""{
            "id":"run-2","conversationId":"conversation-1","taskDescription":"follow up","startTime":20,
            "success":true,"steps":[],"messages":[
                {"role":"USER","text":"first","timestamp":10},
                {"role":"AGENT","text":"done","timestamp":11}
            ]
        }""")
        assertEquals("conversation-1", history.conversationId)
        assertEquals(listOf(HistoryMessageRole.USER, HistoryMessageRole.AGENT), history.messages.map { it.role })
        assertEquals(listOf("first", "done"), history.messages.map { it.text })
    }
}
