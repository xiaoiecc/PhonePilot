package com.kevinluo.autoglm.computer.protocol

import com.kevinluo.autoglm.model.ModelConfig
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedToolsTest {
    @Test fun `official server tools are registered with an automatic code container`() {
        val config = ModelConfig(apiKey = "fixture", modelName = "gpt-6.1-terra")
        val tools = ComputerProtocol.requestBody(config, ComputerProtocol.initialInput("task", "tree"), null)["tools"]!!.jsonArray
        assertEquals(1, tools.count { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" })
        val code = tools.single { it.jsonObject["type"]?.jsonPrimitive?.content == "code_interpreter" }.jsonObject
        assertEquals("auto", code["container"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `hosted calls are observations rather than local commands and image payload is omitted`() {
        val result = ComputerProtocol.parseResponse("""{"id":"r","status":"completed","output":[
            {"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","queries":["weather"]}},
            {"type":"code_interpreter_call","id":"ci_1","status":"completed","container_id":"cn_1","code":"print(1+1)","outputs":[{"type":"logs","logs":"2"},{"type":"image","url":"data:image/png;base64,private-image"}]},
            {"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done"}]}
        ]}""")
        assertTrue(result.calls.isEmpty()); assertTrue(result.functionCalls.isEmpty()); assertTrue(result.outputOrder.isEmpty())
        assertEquals(listOf("web_search", "code_interpreter"), result.hostedCalls.map { it.type })
        assertTrue(result.hostedCalls[0].arguments.contains("weather"))
        assertTrue(result.hostedCalls[1].result.contains("2"))
        assertFalse(result.hostedCalls.toString().contains("private-image"))
    }

    @Test fun `search citations are visibly referenced without breaking unicode and unsafe URLs are ignored`() {
        val value = Json.parseToJsonElement("""{"text":"你好🌍","annotations":[
            {"type":"url_citation","start_index":0,"end_index":3,"title":"Source","url":"https://example.com/ref"},
            {"type":"url_citation","end_index":1,"title":"Bad","url":"javascript:alert(1)"}]}""").jsonObject
        val rendered = HostedResponseFormatting.text(value)
        assertTrue(rendered.startsWith("你好🌍 [1]"))
        assertTrue(rendered.contains("https://example.com/ref"))
        assertFalse(rendered.contains("javascript:"))
    }

    @Test fun `hosted only results are accepted but unknown outputs remain rejected`() {
        assertEquals(1, ComputerProtocol.parseResponse("""{"id":"r","status":"completed","output":[{"type":"web_search_call","id":"ws","status":"completed"}]}""").hostedCalls.size)
        assertThrows(ComputerProtocolException::class.java) {
            ComputerProtocol.parseResponse("""{"id":"r","status":"completed","output":[{"type":"unknown_remote_execution","id":"bad"}]}""")
        }
    }
}
