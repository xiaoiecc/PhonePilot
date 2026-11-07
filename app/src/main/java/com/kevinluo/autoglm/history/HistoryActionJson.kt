package com.kevinluo.autoglm.history

import com.kevinluo.autoglm.action.AgentAction
import kotlinx.serialization.json.*

/** Historical metadata only. This codec has no device or execution dependency. */
object HistoryActionJson {
    private const val MAX_DEPTH = 16

    fun encode(action: AgentAction): JsonObject = encode(action, 0)

    private fun encode(action: AgentAction, depth: Int): JsonObject {
        require(depth <= MAX_DEPTH) { "Action metadata nesting exceeds $MAX_DEPTH" }
        return buildJsonObject {
            put("type", when (action) {
                is AgentAction.Tap -> "Tap"
                is AgentAction.Swipe -> "Swipe"
                is AgentAction.LongPress -> "LongPress"
                is AgentAction.DoubleTap -> "DoubleTap"
                is AgentAction.Type -> "Type"
                is AgentAction.TypeName -> "TypeName"
                is AgentAction.Launch -> "Launch"
                is AgentAction.Wait -> "Wait"
                is AgentAction.TakeOver -> "TakeOver"
                is AgentAction.Interact -> "Interact"
                is AgentAction.Note -> "Note"
                is AgentAction.CallApi -> "CallApi"
                is AgentAction.Finish -> "Finish"
                is AgentAction.Batch -> "Batch"
                AgentAction.Back -> "Back"
                AgentAction.Home -> "Home"
                AgentAction.ListApps -> "ListApps"
                AgentAction.VolumeUp -> "VolumeUp"
                AgentAction.VolumeDown -> "VolumeDown"
                AgentAction.Power -> "Power"
            })
            when (action) {
                is AgentAction.Tap -> { put("x", action.x); put("y", action.y); put("message", action.message) }
                is AgentAction.Swipe -> {
                    put("startX", action.startX); put("startY", action.startY)
                    put("endX", action.endX); put("endY", action.endY); put("humanized", action.humanized)
                }
                is AgentAction.LongPress -> { put("x", action.x); put("y", action.y); put("durationMs", action.durationMs) }
                is AgentAction.DoubleTap -> { put("x", action.x); put("y", action.y) }
                is AgentAction.Type -> put("text", action.text)
                is AgentAction.TypeName -> put("text", action.text)
                is AgentAction.Launch -> put("app", action.app)
                is AgentAction.Wait -> put("durationSeconds", action.durationSeconds)
                is AgentAction.TakeOver -> put("message", action.message)
                is AgentAction.Interact -> put("options", action.options?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                is AgentAction.Note -> put("message", action.message)
                is AgentAction.CallApi -> put("instruction", action.instruction)
                is AgentAction.Finish -> put("message", action.message)
                is AgentAction.Batch -> {
                    put("steps", JsonArray(action.steps.map { encode(it, depth + 1) }))
                    put("delayMs", action.delayMs)
                }
                AgentAction.Back, AgentAction.Home, AgentAction.ListApps, AgentAction.VolumeUp,
                AgentAction.VolumeDown, AgentAction.Power -> Unit
            }
        }
    }

    /** Unknown, corrupt and too deeply nested metadata is omitted rather than replayed. */
    fun decode(json: JsonObject): AgentAction? = decode(json, 0)

    private fun decode(json: JsonObject, depth: Int): AgentAction? {
        if (depth > MAX_DEPTH) return null
        return runCatching {
            fun string(key: String) = requireNotNull((json[key] as? JsonPrimitive)?.contentOrNull)
            fun int(key: String) = requireNotNull((json[key] as? JsonPrimitive)?.intOrNull)
            when (string("type")) {
                "Tap" -> AgentAction.Tap(int("x"), int("y"), (json["message"] as? JsonPrimitive)?.contentOrNull)
                "Swipe" -> AgentAction.Swipe(int("startX"), int("startY"), int("endX"), int("endY"),
                    (json["humanized"] as? JsonPrimitive)?.booleanOrNull ?: true)
                "LongPress" -> AgentAction.LongPress(int("x"), int("y"), (json["durationMs"] as? JsonPrimitive)?.intOrNull ?: 3000)
                "DoubleTap" -> AgentAction.DoubleTap(int("x"), int("y"))
                "Type" -> AgentAction.Type(string("text"))
                "TypeName" -> AgentAction.TypeName(string("text"))
                "Launch" -> AgentAction.Launch(string("app"))
                "Wait" -> AgentAction.Wait(requireNotNull((json["durationSeconds"] as? JsonPrimitive)?.floatOrNull))
                "TakeOver" -> AgentAction.TakeOver(string("message"))
                "Interact" -> AgentAction.Interact((json["options"] as? JsonArray)?.map {
                    requireNotNull((it as? JsonPrimitive)?.contentOrNull)
                })
                "Note" -> AgentAction.Note(string("message"))
                "CallApi" -> AgentAction.CallApi(string("instruction"))
                "Finish" -> AgentAction.Finish(string("message"))
                "Batch" -> AgentAction.Batch((json["steps"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.let { nested -> decode(nested, depth + 1) }
                }, (json["delayMs"] as? JsonPrimitive)?.intOrNull ?: 500)
                "Back" -> AgentAction.Back
                "Home" -> AgentAction.Home
                "ListApps" -> AgentAction.ListApps
                "VolumeUp" -> AgentAction.VolumeUp
                "VolumeDown" -> AgentAction.VolumeDown
                "Power" -> AgentAction.Power
                else -> null
            }
        }.getOrNull()
    }
}
