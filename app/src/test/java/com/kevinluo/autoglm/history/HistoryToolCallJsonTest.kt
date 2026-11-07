package com.kevinluo.autoglm.history

import com.kevinluo.autoglm.action.AgentAction
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryToolCallJsonTest {
    private fun trace(status: HistoryToolStatus = HistoryToolStatus.SUCCEEDED) = HistoryToolCall(
        callId = "call-1", runId = "run-1", stepNumber = 3, name = "computer",
        arguments = "{\"action\":\"tap\"}", startedAt = 42L, finishedAt = 77L,
        status = status, result = "receipt", beforeScreenshotPath = "before.webp",
        afterScreenshotPath = "after.webp", annotatedScreenshotPath = "annotated.webp",
        detailsTruncated = true,
    )

    @Test fun `all trace fields and status values survive a JSON text round trip`() {
        HistoryToolStatus.entries.forEach { status ->
            val call = trace(status)
            val encoded = Json.parseToJsonElement(HistoryToolCallJson.encode(call).toString()).jsonObject
            assertEquals(call, HistoryToolCallJson.decode(encoded))
        }
    }

    @Test fun `every historical action including nested batches round trips as metadata`() {
        val actions = listOf(
            AgentAction.Tap(1, 2, "confirm"), AgentAction.Swipe(1, 2, 3, 4, false),
            AgentAction.LongPress(5, 6, 1000), AgentAction.DoubleTap(7, 8),
            AgentAction.Type("hello\n世界"), AgentAction.TypeName("name"), AgentAction.Launch("app"),
            AgentAction.Wait(1.25f), AgentAction.TakeOver("take over"),
            AgentAction.Interact(listOf("a", "b")), AgentAction.Interact(), AgentAction.Note("note"),
            AgentAction.CallApi("summarize"), AgentAction.Finish("done"), AgentAction.Back,
            AgentAction.Home, AgentAction.ListApps, AgentAction.VolumeUp, AgentAction.VolumeDown, AgentAction.Power,
            AgentAction.Batch(listOf(AgentAction.Tap(9, 10),
                AgentAction.Batch(listOf(AgentAction.Type("nested"), AgentAction.Back), 20)), 10),
        )
        val call = trace().copy(actions = actions)
        assertEquals(call, HistoryToolCallJson.decode(HistoryToolCallJson.encode(call)))
    }

    @Test fun `missing actions and nullable paths retain safe legacy defaults`() {
        val encoded = HistoryToolCallJson.encode(trace()).toMutableMap()
        encoded.remove("actions")
        encoded.remove("detailsTruncated")
        encoded["beforeScreenshotPath"] = JsonNull
        encoded["afterScreenshotPath"] = JsonNull
        encoded.remove("annotatedScreenshotPath")
        val restored = HistoryToolCallJson.decode(JsonObject(encoded))
        assertTrue(restored.actions.isEmpty())
        assertNull(restored.beforeScreenshotPath)
        assertNull(restored.afterScreenshotPath)
        assertNull(restored.annotatedScreenshotPath)
        assertFalse(restored.detailsTruncated)
    }

    @Test fun `unknown malformed and null action metadata cannot discard the valid trace`() {
        val encoded = HistoryToolCallJson.encode(trace()).toMutableMap()
        encoded["actions"] = buildJsonArray {
            add(JsonNull)
            add(buildJsonObject { put("type", "FutureAction") })
            add(buildJsonObject { put("type", "Tap"); put("x", JsonNull) })
            add(HistoryActionJson.encode(AgentAction.Home))
        }
        assertEquals(listOf(AgentAction.Home), HistoryToolCallJson.decode(JsonObject(encoded)).actions)
    }

    @Test fun `required trace fields never silently become null text or default numbers`() {
        val required = listOf("callId", "runId", "stepNumber", "name", "arguments", "startedAt", "finishedAt", "status", "result")
        required.forEach { key ->
            val encoded = HistoryToolCallJson.encode(trace()).toMutableMap()
            encoded.remove(key)
            assertThrows(IllegalArgumentException::class.java) { HistoryToolCallJson.decode(JsonObject(encoded)) }
            encoded[key] = JsonNull
            assertThrows(IllegalArgumentException::class.java) { HistoryToolCallJson.decode(JsonObject(encoded)) }
        }
    }

    @Test fun `nested metadata parsing has a fixed depth bound`() {
        var json = HistoryActionJson.encode(AgentAction.Home)
        repeat(100) { json = buildJsonObject { put("type", "Batch"); put("steps", JsonArray(listOf(json))) } }
        var restored = HistoryActionJson.decode(json)
        var depth = 0
        while (restored is AgentAction.Batch) {
            depth++
            restored = restored.steps.singleOrNull()
        }
        assertEquals(17, depth)
        assertNull(restored)
    }
}
