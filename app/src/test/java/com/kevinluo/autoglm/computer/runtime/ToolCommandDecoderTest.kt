package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.NodeAction
import com.kevinluo.autoglm.accessibility.UiCommand
import com.kevinluo.autoglm.accessibility.SemanticMatchMode
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolCommandDecoderTest {
    @Test fun `dynamic list click is an explicit typed identity request not a relaxed node click`() {
        assertEquals(UiCommand.ClickMatching("s", "row", "Alice, ", SemanticMatchMode.PREFIX),
            ToolCommandDecoder.ui(obj("""{"operation":"click_match","snapshot_id":"s","node_id":"row","query":"Alice, ","match_mode":"prefix"}""")))
        assertEquals(UiCommand.ClickMatching("s", "row", "Alice", SemanticMatchMode.EXACT),
            ToolCommandDecoder.ui(obj("""{"operation":"click_match","snapshot_id":"s","node_id":"row","query":"Alice"}""")))
        listOf(
            """{"operation":"click","snapshot_id":"s","node_id":"row","query":"Alice"}""",
            """{"operation":"click_match","snapshot_id":"s","node_id":"row","query":"Ali","match_mode":"prefix"}""",
            """{"operation":"click_match","snapshot_id":"s","node_id":"row","query":"Alice","match_mode":"fuzzy"}""",
            """{"operation":"click_match","snapshot_id":"s","node_id":"row","query":"Alice","search_scroll":true}""",
            """{"operation":"click_match","snapshot_id":"s","node_id":"row","query":"Alice","scope_id":"another-list"}""",
        ).forEach { input -> assertThrows(Exception::class.java, { ToolCommandDecoder.ui(obj(input)) }, input) }
    }

    @Test fun `every function command is decoded before an earlier device side effect`() = runTest {
        val invalid = listOf(
            function("bad", "android_input_text", """{"text":"","mode":"append"}"""),
            function("bad", "android_wait", """{"duration_ms":60001,"reason":null}"""),
            function("bad", "android_system_key", """{"key":"ESCAPE"}"""),
            function("bad", "android_interact", """{"question":"choose","options":[]}"""),
            function("bad", "android_ui", """{"operation":"set_selection","snapshot_id":"s","node_id":"n","selection_start":4,"selection_end":2}"""),
            function("bad", "android_ui", """{"operation":"custom_action","snapshot_id":"s","node_id":"n","action_id":"custom_999999999999999999"}"""),
        )
        invalid.forEach { bad ->
            val h = RunnerHarness(this)
            h.respond(functionResponse("r1", function("first", "android_system_key", """{"key":"HOME"}"""), bad))
            assertFalse(h.run().success, bad.name)
            coVerify(exactly = 0) { h.device.pressKey(any()) }
        }
    }

    @Test fun `scoped observation find selection range and custom actions retain typed fields`() {
        assertEquals(UiCommand.Observe("s", "scope"), ToolCommandDecoder.ui(obj("""{"operation":"observe","snapshot_id":"s","scope_id":"scope"}""")))
        val find = ToolCommandDecoder.ui(obj("""{"operation":"find","query":"label","snapshot_id":"s","scope_id":"scope","search_scroll":true}"""))
        assertEquals(UiCommand.Find("label", "s", "scope", true), find)
        assertFalse(find.readOnly)
        assertTrue(UiCommand.Find("label", null, null, false).readOnly)
        listOf(
            "\"operation\":\"set_selection\",\"selection_start\":0,\"selection_end\":3" to NodeAction.Selection(0, 3),
            "\"operation\":\"set_progress\",\"progress\":0.5" to NodeAction.Progress(0.5f),
            "\"operation\":\"custom_action\",\"action_id\":\"custom_123\"" to NodeAction.Custom(123),
            "\"operation\":\"append_text\",\"text\":\"suffix\"" to NodeAction.Text("suffix", true),
        ).forEach { (fields, action) ->
            assertEquals(UiCommand.Target("s", "n", action), ToolCommandDecoder.ui(obj("""{"snapshot_id":"s","node_id":"n",$fields}""")))
        }
    }

    @Test fun `explicit false scroll flag is a no-op outside find while true is rejected`() {
        assertEquals(UiCommand.Observe(null, null),
            ToolCommandDecoder.ui(obj("""{"operation":"observe","search_scroll":false}""")))
        assertThrows(Exception::class.java) {
            ToolCommandDecoder.ui(obj("""{"operation":"observe","search_scroll":true}"""))
        }
    }

    @Test fun `invalid AX combinations and primitive coercion are rejected at decode boundary`() {
        listOf(
            """{"operation":"click","snapshot_id":"s","node_id":"n","progress":0.2}""",
            """{"operation":"find","query":"label","search_scroll":"true"}""",
            """{"operation":"observe","scope_id":"n"}""",
            """{"operation":"append_text","snapshot_id":"s","node_id":"n","text":""}""",
            """{"operation":"set_progress","snapshot_id":"s","node_id":"n","progress":"NaN"}""",
            """{"operation":"click","snapshot_id":"s","node_id":"n","unknown":1}""",
        ).forEach { input -> assertThrows(Exception::class.java, { ToolCommandDecoder.ui(obj(input)) }, input) }
    }
}
