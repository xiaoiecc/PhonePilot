package com.kevinluo.autoglm.history

import kotlinx.serialization.json.*

/** Additive history format; action metadata never becomes an executable command. */
object HistoryToolCallJson {
    fun encode(call: HistoryToolCall): JsonObject = buildJsonObject {
        put("callId", call.callId)
        put("runId", call.runId)
        put("stepNumber", call.stepNumber)
        put("name", call.name)
        put("arguments", call.arguments)
        put("startedAt", call.startedAt)
        put("finishedAt", call.finishedAt)
        put("status", call.status.name)
        put("result", call.result)
        put("actions", JsonArray(call.actions.map(HistoryActionJson::encode)))
        put("beforeScreenshotPath", call.beforeScreenshotPath)
        put("afterScreenshotPath", call.afterScreenshotPath)
        put("annotatedScreenshotPath", call.annotatedScreenshotPath)
        put("detailsTruncated", call.detailsTruncated)
    }

    fun decode(json: JsonObject): HistoryToolCall {
        fun primitive(key: String): JsonPrimitive = requireNotNull(json[key] as? JsonPrimitive) { "Missing trace field: $key" }
        fun string(key: String): String = requireNotNull(primitive(key).contentOrNull) { "Null trace field: $key" }
        fun long(key: String): Long = requireNotNull(primitive(key).longOrNull) { "Invalid trace number: $key" }
        return HistoryToolCall(
            callId = string("callId"), runId = string("runId"),
            stepNumber = requireNotNull(primitive("stepNumber").intOrNull),
            name = string("name"), arguments = string("arguments"),
            startedAt = long("startedAt"), finishedAt = long("finishedAt"),
            status = HistoryToolStatus.valueOf(string("status")), result = string("result"),
            actions = (json["actions"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.let(HistoryActionJson::decode)
            },
            beforeScreenshotPath = (json["beforeScreenshotPath"] as? JsonPrimitive)?.contentOrNull,
            afterScreenshotPath = (json["afterScreenshotPath"] as? JsonPrimitive)?.contentOrNull,
            annotatedScreenshotPath = (json["annotatedScreenshotPath"] as? JsonPrimitive)?.contentOrNull,
            detailsTruncated = (json["detailsTruncated"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}
