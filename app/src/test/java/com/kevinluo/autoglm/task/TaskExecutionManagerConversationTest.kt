package com.kevinluo.autoglm.task

import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.history.HistoryMessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `message history restores multiple image references and remains compatible with old text messages`() {
        val history = HistoryManager.taskHistoryFromJson("""{
            "id":"run-images","taskDescription":"look","startTime":20,
            "success":true,"steps":[],"messages":[
                {"role":"USER","text":"","timestamp":10,"images":[
                    {"path":"/private/first.webp","mimeType":"image/webp"},
                    {"path":"/private/second.webp","mimeType":"image/webp"}
                ]},
                {"role":"AGENT","text":"done","timestamp":11}
            ]
        }""")
        assertEquals(listOf("/private/first.webp", "/private/second.webp"), history.messages.first().images.map { it.path })
        assertTrue(history.messages.last().images.isEmpty())
    }
}
