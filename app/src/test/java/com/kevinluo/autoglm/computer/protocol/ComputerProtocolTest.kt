package com.kevinluo.autoglm.computer.protocol

import com.kevinluo.autoglm.model.ModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComputerProtocolTest {
    private val config = ModelConfig(
        baseUrl = "https://api.openai.com/v1",
        apiKey = "test-key",
        modelName = "gpt-6.1-terra",
    )

    @Test
    fun `only approved Sol and Terra models can be requested`() {
        val input = ComputerProtocol.initialInput("Open Settings", "UklGR-example", 720, 1280)
        assertEquals("gpt-6.1-sol", ComputerProtocol.requestBody(config.copy(modelName = "gpt-6.1-sol"), input, null)["model"]?.jsonPrimitive?.content)
        assertThrows(IllegalArgumentException::class.java) {
            ComputerProtocol.requestBody(config.copy(modelName = "computer-use-preview"), input, null)
        }
    }

    @Test
    fun `initial request follows Responses format without legacy sampling or commands`() {
        val input = ComputerProtocol.initialInput("Open Settings", "UklGR-example", 720, 1280)
        val request = ComputerProtocol.requestBody(config, input, null)
        assertEquals("gpt-6.1-terra", request["model"]?.jsonPrimitive?.content)
        val tools = request["tools"]!!.jsonArray.map { it.jsonObject }
        assertTrue(tools.none { it["type"]?.jsonPrimitive?.content == "computer" })
        assertTrue(tools.any { it["name"]?.jsonPrimitive?.content == "android_computer" })
        val content = input.single().jsonObject["content"]!!.jsonArray
        assertEquals("input_text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("720x1280"))
        assertEquals("input_image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("data:image/webp;base64,UklGR-example", content[1].jsonObject["image_url"]?.jsonPrimitive?.content)
        assertEquals("original", content[1].jsonObject["detail"]?.jsonPrimitive?.content)
        listOf("messages", "temperature", "top_p", "frequency_penalty", "previous_response_id", "max_tokens").forEach {
            assertFalse(request.containsKey(it))
        }
        assertEquals("medium", request["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `one user message retains multiple image order and allows image only input`() {
        val images = listOf(
            ResponsesImage("image/webp", "first-image"),
            ResponsesImage("image/png", "iVBOR-second"),
            ResponsesImage("image/jpeg", "/9j/third"),
        )
        val message = ComputerProtocol.userInput("", images)
        val content = message["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals(listOf("first-image", "iVBOR-second", "/9j/third"), content.map {
            it.jsonObject["image_url"]!!.jsonPrimitive.content.substringAfter("base64,")
        })
        assertEquals(listOf("image/webp", "image/png", "image/jpeg"), content.map {
            it.jsonObject["image_url"]!!.jsonPrimitive.content.substringAfter("data:").substringBefore(';')
        })
        assertTrue(content.all { it.jsonObject["type"]!!.jsonPrimitive.content == "input_image" })
        assertTrue(content.all { it.jsonObject["detail"]!!.jsonPrimitive.content == "original" })
        assertThrows(IllegalArgumentException::class.java) { ComputerProtocol.userInput("  ", emptyList()) }
    }

    @Test
    fun `multi image requests use the function replacement and retain every image`() {
        val message = ComputerProtocol.userInput("Compare", listOf(
            ResponsesImage("image/webp", "first"), ResponsesImage("image/webp", "second"),
        ))
        val request = ComputerProtocol.requestBody(config, JsonArray(listOf(message)), null)
        val tools = request["tools"]!!.jsonArray.map { it.jsonObject }
        assertTrue(tools.none { it["type"]?.jsonPrimitive?.content == "computer" })
        assertTrue(tools.any { it["name"]?.jsonPrimitive?.content == "android_computer" })
        assertTrue(tools.any { it["name"]?.jsonPrimitive?.content == "android_ui" })
        assertTrue(tools.any { it["name"]?.jsonPrimitive?.content == "android_gesture" })
        assertTrue(tools.any { it["name"]?.jsonPrimitive?.content == "android_screenshot" })
        assertEquals(3, message["content"]!!.jsonArray.size)
    }

    @Test
    fun `continuation associates screenshot with the original call and response`() {
        val output = ComputerProtocol.screenshotOutput("call-1", "iVBOR-png")
        val body = ComputerProtocol.requestBody(config, JsonArray(listOf(output)), "resp-1")
        assertEquals("resp-1", body["previous_response_id"]?.jsonPrimitive?.content)
        assertEquals("computer_call_output", output["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", output["call_id"]?.jsonPrimitive?.content)
        assertEquals("computer_screenshot", output["output"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,iVBOR-png", output["output"]!!.jsonObject["image_url"]?.jsonPrimitive?.content)
        assertFalse(output.containsKey("acknowledged_safety_checks"))
    }

    @Test
    fun `all computer calls and actions retain order and safety checks`() {
        val response = ComputerProtocol.parseResponse(
            """{
              "id":"resp-1","status":"completed","output":[
                {"type":"reasoning","summary":[]},
                {"type":"computer_call","status":"completed","call_id":"call-1",
                 "actions":[{"type":"click","x":10,"y":20,"button":"left"},{"type":"type","text":"hello"}],
                 "pending_safety_checks":[{"id":"check-1","code":"sensitive_domain","message":"Review"}]},
                {"type":"computer_call","status":"completed","call_id":"call-2","actions":[{"type":"screenshot"}]}
              ]
            }""",
        )
        assertEquals(listOf("call-1", "call-2"), response.calls.map { it.callId })
        assertEquals(listOf("click", "type"), response.calls[0].actions.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("check-1", response.calls[0].pendingSafetyChecks.single()["id"]?.jsonPrimitive?.content)
        assertTrue(response.calls[1].pendingSafetyChecks.isEmpty())
    }

    @Test
    fun `completed assistant text is preserved`() {
        val response = ComputerProtocol.parseResponse(
            """{"id":"resp-2","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Settings is open."}]}]}""",
        )
        assertEquals("Settings is open.", response.text)
        assertTrue(response.calls.isEmpty())
    }

    @Test
    fun `incomplete errors malformed or unsupported output never imply success`() {
        val invalid = listOf(
            "not json",
            """{"id":"r","status":"incomplete","output":[]}""",
            """{"id":"r","status":"completed","error":{"message":"private detail"},"output":[]}""",
            """{"id":"r","status":"completed","output":[]}""",
            """{"id":"r","status":"completed","output":[{"type":"function_call","name":"shell"}]}""",
            """{"id":"r","status":"completed","output":[{"type":"computer_call","call_id":"c","status":"completed","actions":[]}]}""",
            """{"id":"r","status":"completed","output":[{"type":"computer_call","call_id":"c","status":"completed","actions":[{"type":"shell"}]}]}""",
            """{"id":"r","status":"completed","output":[{"type":"computer_call","call_id":"c","status":"in_progress","actions":[{"type":"click"}]}]}""",
            """{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"No"}]}]}""",
        )
        invalid.forEach { body ->
            val error = assertThrows(ComputerProtocolException::class.java) { ComputerProtocol.parseResponse(body) }
            assertFalse(error.message!!.contains("private detail"))
        }
    }

    @Test
    fun `duplicate call identifiers and malformed safety checks are rejected`() {
        val call = Json.parseToJsonElement(
            """{"type":"computer_call","call_id":"c","status":"completed","actions":[{"type":"wait"}]}""",
        ).jsonObject
        val duplicate = """{"id":"r","status":"completed","output":[$call,$call]}"""
        assertThrows(ComputerProtocolException::class.java) { ComputerProtocol.parseResponse(duplicate) }
        val badChecks = JsonObject(call + ("pending_safety_checks" to JsonArray(listOf(JsonArray(emptyList())))))
        assertThrows(ComputerProtocolException::class.java) {
            ComputerProtocol.parseResponse("""{"id":"r","status":"completed","output":[$badChecks]}""")
        }
    }

    @Test
    fun `reasoning effort is configurable with model specific validation`() {
        val input = ComputerProtocol.initialInput("Open Settings", "tree")
        assertEquals("none", ComputerProtocol.requestBody(config.copy(reasoningEffort = "none"), input, null)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            ComputerProtocol.requestBody(config.copy(modelName = "gpt-6.1-sol", reasoningEffort = "none"), input, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComputerProtocol.requestBody(config.copy(reasoningEffort = "invented"), input, null)
        }
    }

    @Test
    fun `text initial observation and function screenshots use official content formats`() {
        val input = ComputerProtocol.initialInput("Read Settings", "snapshot_id=s1")
        assertFalse(input.toString().contains("input_image"))
        val screenshot = ComputerProtocol.functionScreenshotOutput("f1", "UklGR-example", 720, 1280)
        assertEquals("function_call_output", screenshot["type"]!!.jsonPrimitive.content)
        assertEquals("f1", screenshot["call_id"]!!.jsonPrimitive.content)
        assertEquals("input_image", screenshot["output"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("original", screenshot["output"]!!.jsonArray[1].jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals("done", ComputerProtocol.functionOutput("f1", "done")["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `function and computer interleaving retains wire order`() {
        val body = """{"id":"r","status":"completed","output":[
            {"type":"function_call","status":"completed","call_id":"f1","name":"android_ui","arguments":"{\"operation\":\"observe\",\"snapshot_id\":null,\"node_id\":null,\"text\":null,\"commentary\":\"Read the current page.\"}"},
            {"type":"computer_call","status":"completed","call_id":"c1","actions":[{"type":"screenshot"}]},
            {"type":"function_call","status":"completed","call_id":"f2","name":"android_screenshot","arguments":"{\"commentary\":\"Inspect the image details.\"}"}
        ]}"""
        val response = ComputerProtocol.parseResponse(body)
        assertEquals(listOf("f1", "c1", "f2"), response.outputOrder)
        assertEquals(listOf("android_ui", "android_screenshot"), response.functionCalls.map { it.name })
    }

    @Test
    fun `strict tools declare required nullable arguments and reject wrong action inputs`() {
        val request = ComputerProtocol.requestBody(config, ComputerProtocol.initialInput("task", "tree"), null)
        val ui = request["tools"]!!.jsonArray[0].jsonObject
        assertEquals("true", ui["strict"]!!.jsonPrimitive.content)
        assertEquals(13, ui["parameters"]!!.jsonObject["required"]!!.jsonArray.size)
        val bad = """{"id":"r","status":"completed","output":[{"type":"function_call","status":"completed","call_id":"f","name":"android_ui","arguments":"{\"operation\":\"click\",\"snapshot_id\":null,\"node_id\":null,\"text\":null,\"commentary\":\"Open the selected item.\"}"}]}"""
        assertThrows(ComputerProtocolException::class.java) { ComputerProtocol.parseResponse(bad) }
    }

    @Test
    fun `all legacy capabilities are registered as strict Responses function tools`() {
        val request = ComputerProtocol.requestBody(config, ComputerProtocol.initialInput("task", "tree"), null)
        val functions = request["tools"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "function" }
        assertEquals(setOf(
            "android_ui", "android_screenshot", "android_computer", "android_gesture", "android_input_text",
            "android_launch_app", "android_list_apps", "android_system_key", "android_take_over",
            "android_interact", "android_note", "android_ask_user", "android_wait",
        ), functions.map { it["name"]!!.jsonPrimitive.content }.toSet())
        functions.forEach { tool ->
            assertTrue(tool["strict"]!!.jsonPrimitive.content.toBoolean())
            assertFalse(tool["parameters"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
        }
        val computer = functions.single { it["name"]!!.jsonPrimitive.content == "android_computer" }
        val variants = computer["parameters"]!!.jsonObject["properties"]!!.jsonObject["actions"]!!.jsonObject["items"]!!.jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject }
        val actionTypes = variants.map { variant ->
            variant["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content
        }.toSet()
        assertEquals(setOf("click", "double_click", "drag", "move", "scroll", "keypress", "type", "wait", "screenshot"), actionTypes)
        val click = variants.single { it["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content == "click" }
        assertEquals("string", click["properties"]!!.jsonObject["button"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("left", "wheel", "back", "forward"),
            click["properties"]!!.jsonObject["button"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(click["required"]!!.jsonArray.contains(JsonPrimitive("button")))
        assertFalse(click["properties"]!!.jsonObject.containsKey("keys"))
        val keypress = variants.single { it["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content == "keypress" }
        assertEquals(1, keypress["properties"]!!.jsonObject["keys"]!!.jsonObject["maxItems"]!!.jsonPrimitive.content.toInt())

        val gesture = functions.single { it["name"]!!.jsonPrimitive.content == "android_gesture" }
        val gestureProperties = gesture["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertFalse(gestureProperties["x"]!!.jsonObject.containsKey("minimum"))
        assertEquals(1, gestureProperties["duration_ms"]!!.jsonObject["minimum"]!!.jsonPrimitive.content.toInt())
        assertEquals(5000, gestureProperties["duration_ms"]!!.jsonObject["maximum"]!!.jsonPrimitive.content.toInt())

        val valid = listOf(
            "android_computer" to objectOf("""{"actions":[{"type":"click","x":10,"y":20,"button":"left"}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"double_click","x":10,"y":20}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"drag","path":[{"x":10,"y":20},{"x":30,"y":40}]}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"move","x":10,"y":20}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"scroll","x":10,"y":20,"scroll_x":0,"scroll_y":100}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"keypress","keys":["ENTER"]}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"type","text":"hello"}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"wait"}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"screenshot"}]}"""),
            "android_gesture" to objectOf("""{"operation":"swipe","x":10,"y":20,"end_x":30,"end_y":40,"duration_ms":400,"humanized":true,"confirmation_message":null}"""),
            "android_gesture" to objectOf("""{"operation":"long_press","x":10,"y":20,"end_x":null,"end_y":null,"duration_ms":3000,"humanized":false,"confirmation_message":"Confirm"}"""),
            "android_input_text" to objectOf("""{"text":"hello","mode":"replace"}"""),
            "android_input_text" to objectOf("""{"text":" world","mode":"append"}"""),
            "android_launch_app" to objectOf("""{"app":"WeChat"}"""),
            "android_list_apps" to objectOf("{}"),
            "android_system_key" to objectOf("""{"key":"VOLUME_UP"}"""),
            "android_take_over" to objectOf("""{"message":"Please unlock"}"""),
            "android_interact" to objectOf("""{"question":"Choose","options":["A","B"]}"""),
            "android_note" to objectOf("""{"message":"Remember this"}"""),
        )
        valid.forEach { (name, arguments) -> assertEquals(name, parseFunction(name, arguments).name) }
    }

    @Test
    fun `new function tools reject malformed arguments before execution`() {
        val invalid = listOf(
            "android_computer" to objectOf("""{"actions":[{"type":"click","x":241,"y":196,"button":null}]}"""),
            "android_computer" to objectOf("""{"actions":[{"type":"click","x":10,"y":20,"button":"left","keys":null}]}"""),
            "android_gesture" to objectOf("""{"operation":"swipe","x":10,"y":20,"end_x":null,"end_y":40,"duration_ms":400,"humanized":true,"confirmation_message":null}"""),
            "android_gesture" to objectOf("""{"operation":"swipe","x":10,"y":20,"end_x":30,"end_y":40,"duration_ms":0,"humanized":true,"confirmation_message":null}"""),
            "android_gesture" to objectOf("""{"operation":"long_press","x":10,"y":20,"end_x":null,"end_y":null,"duration_ms":5001,"humanized":false,"confirmation_message":null}"""),
            "android_gesture" to objectOf("""{"operation":"jump","x":10,"y":20,"end_x":null,"end_y":null,"duration_ms":400,"humanized":false,"confirmation_message":null}"""),
            "android_input_text" to objectOf("""{"text":"","mode":"append"}"""),
            "android_input_text" to objectOf("""{"text":"hello","mode":"overwrite"}"""),
            "android_launch_app" to objectOf("""{"app":" "}"""),
            "android_list_apps" to objectOf("""{"unexpected":true}"""),
            "android_system_key" to objectOf("""{"key":"MUTE"}"""),
            "android_take_over" to objectOf("""{"message":""}"""),
            "android_interact" to objectOf("""{"question":"Choose","options":[]}"""),
            "android_interact" to objectOf("""{"question":"Choose","options":["A","B","C","D","E","F","G","H","I"]}"""),
            "android_note" to objectOf("""{"message":" "}"""),
        )
        invalid.forEach { (name, arguments) ->
            assertThrows(ComputerProtocolException::class.java) { parseFunction(name, arguments) }
        }
        assertThrows(ComputerProtocolException::class.java) {
            parseFunction("android_launch_app", objectOf("""{"app":"WeChat","unexpected":true}"""))
        }
        assertThrows(ComputerProtocolException::class.java) {
            parseFunction("android_input_text", objectOf(buildJsonObject {
                put("text", "x".repeat(20001)); put("mode", "replace")
            }.toString()))
        }
    }

    @Test
    fun `ask user is a strict free text function`() {
        val call = parseFunction("android_ask_user", objectOf("""{"question":"What should I send?"}"""))
        assertEquals("android_ask_user", call.name)
        assertThrows(ComputerProtocolException::class.java) {
            parseFunction("android_ask_user", objectOf("""{"question":" "}"""))
        }
        assertThrows(ComputerProtocolException::class.java) {
            parseFunction("android_ask_user", objectOf("""{"question":"Choose","unexpected":true}"""))
        }
    }

    @Test
    fun `semantic actions validate their operation specific arguments`() {
        listOf("long_click", "append_text", "show", "set_selection", "set_progress", "custom_action",
            "scroll_forward", "scroll_backward", "scroll_up", "scroll_down", "scroll_left", "scroll_right").forEach { operation ->
            val args = uiArguments(operation)
            assertEquals(operation, parseFunction("android_ui", args).arguments["operation"]!!.jsonPrimitive.content)
        }
        listOf(
            uiArguments("set_selection").let { JsonObject(it + mapOf("selection_start" to JsonPrimitive(-1))) },
            uiArguments("set_selection").let { JsonObject(it + mapOf("selection_start" to JsonPrimitive(3))) },
            uiArguments("set_progress").let { JsonObject(it + mapOf("progress" to JsonPrimitive("half"))) },
            uiArguments("custom_action").let { JsonObject(it + mapOf("action_id" to JsonPrimitive("invented"))) },
        ).forEach { args -> assertThrows(ComputerProtocolException::class.java) { parseFunction("android_ui", args) } }
    }

    private fun uiArguments(operation: String): JsonObject = buildJsonObject {
        put("operation", operation)
        put("snapshot_id", "s1")
        put("node_id", "n1")
        put("text", if (operation == "append_text") JsonPrimitive("hello") else JsonNull)
        put("selection_start", if (operation == "set_selection") JsonPrimitive(1) else JsonNull)
        put("selection_end", if (operation == "set_selection") JsonPrimitive(2) else JsonNull)
        put("progress", if (operation == "set_progress") JsonPrimitive(0.5) else JsonNull)
        put("action_id", if (operation == "custom_action") JsonPrimitive("custom_42") else JsonNull)
    }

    @Test
    fun `dynamic matching extends strict schema while legacy semantic requests remain readable`() {
        val args = JsonObject(uiArguments("click_match") + mapOf(
            "query" to JsonPrimitive("Alice, "), "search_scroll" to JsonNull,
            "scope_id" to JsonNull, "match_mode" to JsonPrimitive("prefix")))
        assertEquals("prefix", parseFunction("android_ui", args).arguments["match_mode"]!!.jsonPrimitive.content)
        parseFunction("android_ui", uiArguments("click"))
        assertThrows(ComputerProtocolException::class.java) {
            parseFunction("android_ui", JsonObject(args + ("match_mode" to JsonPrimitive("fuzzy"))))
        }
        assertThrows(ComputerProtocolException::class.java) {
            parseFunction("android_ui", JsonObject(args + ("operation" to JsonPrimitive("click"))))
        }
        val tools = ComputerProtocol.requestBody(config, ComputerProtocol.initialInput("task", "tree"), null)["tools"]!!.jsonArray
        val matching = tools.map { it.jsonObject }.filter { it["name"]?.jsonPrimitive?.content == "android_ui" }
        assertEquals(1, matching.size, matching.toString())
        val ui = matching.single()
        val schema = ui["parameters"]!!.jsonObject
        assertEquals(schema["properties"]!!.jsonObject.keys,
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(schema["properties"]!!.jsonObject["operation"]!!.jsonObject["enum"]!!.jsonArray.contains(JsonPrimitive("click_match")))
    }

    @Test
    fun `android wait accepts nullable defaults and enforces its duration range`() {
        assertEquals("android_wait", parseFunction("android_wait", objectOf("""{"duration_ms":null,"reason":null}""")).name)
        assertEquals("android_wait", parseFunction("android_wait", objectOf("""{"duration_ms":60000,"reason":"loading"}""")).name)
        listOf(-1, 60001).forEach { duration ->
            assertThrows(ComputerProtocolException::class.java) {
                parseFunction("android_wait", objectOf("""{"duration_ms":$duration,"reason":null}"""))
            }
        }
    }

    @Test
    fun `every local function requires bounded non nullable commentary while hosted tools keep their native schemas`() {
        val tools = ComputerProtocol.requestBody(config, ComputerProtocol.initialInput("task", "tree"), null)["tools"]!!.jsonArray
        tools.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }.forEach { value ->
            val parameters = value.jsonObject["parameters"]!!.jsonObject
            val properties = parameters["properties"]!!.jsonObject
            assertEquals(properties.keys, parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            val commentary = properties["commentary"]!!.jsonObject
            assertEquals("string", commentary["type"]!!.jsonPrimitive.content)
            assertEquals(1, commentary["minLength"]!!.jsonPrimitive.content.toInt())
            assertEquals(280, commentary["maxLength"]!!.jsonPrimitive.content.toInt())
        }
        tools.filter { it.jsonObject["type"]?.jsonPrimitive?.content != "function" }.forEach {
            assertFalse(it.jsonObject.containsKey("parameters"))
            assertFalse(it.toString().contains("commentary"))
        }
        val call = parseFunction("android_screenshot", objectOf("""{"commentary":"我先确认一下这道图片题。"}"""))
        assertEquals("我先确认一下这道图片题。", call.arguments["commentary"]!!.jsonPrimitive.content)
        assertEquals(280, parseRawFunction("android_screenshot", buildJsonObject { put("commentary", "x".repeat(280)) })
            .arguments["commentary"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `missing null blank wrong type and oversized commentary are rejected at the protocol boundary`() {
        val invalid = listOf(objectOf("{}"), objectOf("""{"commentary":null}"""),
            objectOf("""{"commentary":42}"""), objectOf("""{"commentary":false}"""),
            objectOf("""{"commentary":""}"""), buildJsonObject { put("commentary", "  \n\t") },
            buildJsonObject { put("commentary", "x".repeat(281)) })
        invalid.forEach { arguments ->
            assertThrows(ComputerProtocolException::class.java) {
                parseRawFunction("android_screenshot", arguments)
            }
        }
    }

    @Test
    fun `a computer batch uses one commentary and its internal actions do not need it`() {
        val arguments = objectOf("""{"commentary":"打开菜单并向下查看选项。","actions":[
            {"type":"click","x":10,"y":20,"button":"left"},
            {"type":"scroll","x":10,"y":20,"scroll_x":0,"scroll_y":100}
        ]}""")
        val call = parseRawFunction("android_computer", arguments)
        assertEquals(2, call.arguments["actions"]!!.jsonArray.size)
        assertTrue(call.arguments["actions"]!!.jsonArray.all { !it.jsonObject.containsKey("commentary") })
    }

    private fun objectOf(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun parseFunction(name: String, arguments: JsonObject) = parseRawFunction(name,
        if ("commentary" in arguments) arguments else JsonObject(arguments + ("commentary" to JsonPrimitive("Explain this test action."))))

    private fun parseRawFunction(name: String, arguments: JsonObject) = ComputerProtocol.parseResponse(buildJsonObject {
        put("id", "r-function")
        put("status", "completed")
        put("output", buildJsonArray {
            add(buildJsonObject {
                put("type", "function_call")
                put("status", "completed")
                put("call_id", "call-function")
                put("name", name)
                put("arguments", arguments.toString())
            })
        })
    }.toString()).functionCalls.single()
}
