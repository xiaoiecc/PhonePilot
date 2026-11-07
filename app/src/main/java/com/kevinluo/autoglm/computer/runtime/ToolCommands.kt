package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.NodeAction
import com.kevinluo.autoglm.accessibility.UiCommand
import com.kevinluo.autoglm.accessibility.SemanticMatchMode
import com.kevinluo.autoglm.computer.protocol.ComputerFunctionCall
import kotlinx.serialization.json.*

internal enum class InputMode { REPLACE, APPEND }
internal enum class SystemKey(val code: Int) { BACK(4), HOME(3), VOLUME_UP(24), VOLUME_DOWN(25), POWER(26) }
internal sealed interface Gesture {
    data object LongPress : Gesture
    data class Swipe(val endX: Int, val endY: Int, val humanized: Boolean) : Gesture
}
internal sealed interface FunctionCommand {
    data class Launch(val app: String) : FunctionCommand
    data object ListApps : FunctionCommand
    data class Key(val key: SystemKey) : FunctionCommand
    data class Input(val text: String, val mode: InputMode) : FunctionCommand
    data class GestureCommand(val x: Int, val y: Int, val durationMs: Int, val gesture: Gesture, val confirmation: String?) : FunctionCommand
    data class Takeover(val message: String) : FunctionCommand
    data class Choice(val question: String, val options: List<String>) : FunctionCommand
    data class Ask(val question: String) : FunctionCommand
    data class Wait(val durationMs: Int?, val reason: String?) : FunctionCommand
    data class Note(val message: String) : FunctionCommand
}

/** Single JSON decoding edge, run for the entire batch before any IO. */
internal object ToolCommandDecoder {
    fun commentary(args: JsonObject): String? = Fields(args).string("commentary")?.also { require(it.length <= 280) }
    fun capture(args: JsonObject) { Fields(args).only() }
    fun function(call: ComputerFunctionCall): FunctionCommand = with(Fields(call.arguments)) {
        when (call.name) {
            "android_launch_app" -> { only("app"); FunctionCommand.Launch(required("app")) }
            "android_list_apps" -> { only(); FunctionCommand.ListApps }
            "android_system_key" -> { only("key"); FunctionCommand.Key(SystemKey.valueOf(required("key"))) }
            "android_input_text" -> {
                only("text", "mode")
                val text = string("text") ?: error("Input requires text")
                val mode = when (required("mode")) { "replace" -> InputMode.REPLACE; "append" -> InputMode.APPEND; else -> error("Invalid input mode") }
                require(text.length <= 20000 && (mode != InputMode.APPEND || text.isNotEmpty()))
                FunctionCommand.Input(text, mode)
            }
            "android_gesture" -> {
                only("operation", "x", "y", "end_x", "end_y", "duration_ms", "humanized", "confirmation_message")
                val x = integer("x")!!; val y = integer("y")!!
                require(x >= 0 && y >= 0)
                val duration = integer("duration_ms")!!; require(duration in 1..5000)
                val humanized = boolean("humanized") ?: error("Missing humanized")
                val gesture = when (required("operation")) {
                    "long_press" -> { require(integer("end_x") == null && integer("end_y") == null); Gesture.LongPress }
                    "swipe" -> Gesture.Swipe(integer("end_x")!!, integer("end_y")!!, humanized).also { require(it.endX >= 0 && it.endY >= 0) }
                    else -> error("Invalid gesture")
                }
                FunctionCommand.GestureCommand(x, y, duration, gesture, string("confirmation_message")?.also { require(it.isNotBlank()) })
            }
            "android_take_over" -> { only("message"); FunctionCommand.Takeover(required("message")) }
            "android_note" -> { only("message"); FunctionCommand.Note(required("message")) }
            "android_ask_user" -> { only("question"); FunctionCommand.Ask(required("question")) }
            "android_interact" -> {
                only("question", "options")
                val options = (args["options"] as? JsonArray ?: error("Missing options")).map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: error("Invalid option") }
                require(options.size in 1..8)
                FunctionCommand.Choice(required("question"), options.toList())
            }
            "android_wait" -> {
                only("duration_ms", "reason")
                FunctionCommand.Wait(integer("duration_ms")?.also { require(it in 0..60000) }, string("reason")?.also { require(it.isNotBlank()) })
            }
            else -> error("Unsupported function tool")
        }
    }
    fun ui(args: JsonObject): UiCommand = with(Fields(args)) {
        only("operation", "snapshot_id", "node_id", "text", "selection_start", "selection_end", "progress", "action_id", "query", "search_scroll", "scope_id", "match_mode")
        val operation = required("operation")
        val snapshot = string("snapshot_id"); val node = string("node_id"); val scope = string("scope_id")
        val text = string("text"); val query = string("query"); val scroll = boolean("search_scroll")
        val start = integer("selection_start"); val end = integer("selection_end")
        val progress = number("progress"); val actionId = string("action_id")
        val matchMode = string("match_mode")
        require(text == null || operation in setOf("set_text", "append_text"))
        require(query == null || operation in setOf("find", "click_match"))
        require(matchMode == null || operation == "click_match")
        require(scroll == null || operation == "find")
        require(scope == null || operation in setOf("observe", "find"))
        require(start == null && end == null || operation == "set_selection")
        require(progress == null || operation == "set_progress")
        require(actionId == null || operation == "custom_action")
        if (scope != null) require(!snapshot.isNullOrBlank() && scope.isNotBlank())
        when (operation) {
            "observe" -> { require(node == null); UiCommand.Observe(snapshot, scope) }
            "find" -> { require(node == null); UiCommand.Find(query?.takeIf { it.isNotBlank() } ?: error("Missing query"), snapshot, scope, scroll ?: false) }
            "click_match" -> {
                require(!snapshot.isNullOrBlank() && !node.isNullOrBlank())
                val mode = when (matchMode) {
                    null, "exact" -> SemanticMatchMode.EXACT
                    "prefix" -> SemanticMatchMode.PREFIX
                    else -> error("Invalid semantic match mode")
                }
                val target = query?.takeIf { it.isNotBlank() && it.length <= 1000 } ?: error("Missing or overlong matching query")
                if (mode == SemanticMatchMode.PREFIX) require(target.trimEnd(' ').last() in ",，:：;；\n\t" && target.any(Char::isLetterOrDigit))
                UiCommand.ClickMatching(snapshot, node, target, mode)
            }
            else -> {
                require(!snapshot.isNullOrBlank() && !node.isNullOrBlank())
                val action = when (operation) {
                    "click" -> NodeAction.Simple.CLICK
                    "long_click" -> NodeAction.Simple.LONG_CLICK
                    "scroll_forward" -> NodeAction.Simple.SCROLL_FORWARD
                    "scroll_backward" -> NodeAction.Simple.SCROLL_BACKWARD
                    "show" -> NodeAction.Simple.SHOW
                    "set_text", "append_text" -> NodeAction.Text(text ?: error("Missing text"), operation == "append_text").also { require(it.value.length <= 10000 && (!it.append || it.value.isNotEmpty())) }
                    "set_selection" -> { require(start != null && end != null && start >= 0 && end >= start); NodeAction.Selection(start, end) }
                    "set_progress" -> NodeAction.Progress(progress ?: error("Missing progress"))
                    "custom_action" -> { require(actionId?.matches(Regex("custom_\\d+")) == true); NodeAction.Custom(actionId!!.removePrefix("custom_").toIntOrNull()?.takeIf { it > 0 } ?: error("Invalid custom action")) }
                    else -> error("Unknown accessibility operation")
                }
                UiCommand.Target(snapshot, node, action)
            }
        }
    }
    private class Fields(val args: JsonObject) {
        fun only(vararg names: String) { require((args.keys - "commentary").all { it in names }); commentary(args) }
        private fun primitive(key: String): JsonPrimitive? = args[key]?.takeUnless { it == JsonNull }?.let { it as? JsonPrimitive ?: error("Invalid $key") }
        fun string(key: String): String? = primitive(key)?.let { require(it.isString); it.content }
        fun required(key: String): String = string(key)?.takeIf { it.isNotBlank() } ?: error("Missing $key")
        fun integer(key: String): Int? = primitive(key)?.let { require(!it.isString); it.intOrNull ?: error("Invalid $key") }
        fun number(key: String): Float? = primitive(key)?.let { require(!it.isString); it.floatOrNull?.takeIf(Float::isFinite) ?: error("Invalid $key") }
        fun boolean(key: String): Boolean? = primitive(key)?.let { require(!it.isString); it.booleanOrNull ?: error("Invalid $key") }
    }
}
