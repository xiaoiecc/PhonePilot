package com.kevinluo.autoglm.computer.protocol

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Bounded read-only hosted-tool metadata and visible source references. */
internal object HostedResponseFormatting {
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun trace(item: JsonObject, id: String, status: String): HostedToolCall {
        val type = checkNotNull(item.string("type"))
        val args = buildJsonObject {
            if (type == "web_search_call") {
                val action = item["action"] as? JsonObject
                for (key in listOf("type", "query", "queries", "url", "pattern")) {
                    action?.get(key)?.let { value ->
                        val bounded = value.toString().take(12000)
                        put(key, if (bounded.contains("data:image/")) "[image data omitted]" else bounded)
                    }
                }
            } else {
                put("code", item.string("code").orEmpty().take(12000))
                item.string("container_id")?.let { put("container_id", it.take(200)) }
            }
        }
        val logs = (item["outputs"] as? JsonArray).orEmpty().take(20).mapNotNull { value ->
            val output = value as? JsonObject ?: return@mapNotNull null
            when (output.string("type")) {
                "logs" -> output.string("logs")?.take(12000)
                "image" -> "[Cloud image output; image payload omitted]"
                else -> null
            }
        }.joinToString("\n").take(12000)
        return HostedToolCall(id, type.removeSuffix("_call"), status, args.toString(),
            "OpenAI server tool: $status" + if (logs.isBlank()) "" else "\n$logs")
    }

    fun text(content: JsonObject): String {
        val text = content.string("text")?.takeIf { it.isNotBlank() } ?: throw ComputerProtocolException("Missing output text")
        val sources = (content["annotations"] as? JsonArray).orEmpty().mapNotNull { value ->
            val annotation = value as? JsonObject ?: return@mapNotNull null
            if (annotation.string("type") != "url_citation") return@mapNotNull null
            val url = annotation.string("url")?.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
                ?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: return@mapNotNull null
            val end = (annotation["end_index"] as? JsonPrimitive)?.intOrNull
            val offset = end?.takeIf { it in 0..text.codePointCount(0, text.length) }
                ?.let { text.offsetByCodePoints(0, it) } ?: text.length
            Triple(offset, annotation.string("title").orEmpty().replace('\n', ' ').take(200), url.toString())
        }.take(30)
        if (sources.isEmpty()) return text
        val rendered = StringBuilder(text)
        sources.withIndex().sortedByDescending { it.value.first }.forEach { (index, source) ->
            rendered.insert(source.first, " [${index + 1}]")
        }
        rendered.append("\n\n")
        sources.forEachIndexed { index, source ->
            rendered.append("[${index + 1}] ").append(source.second).append(' ').append(source.third).append('\n')
        }
        return rendered.toString().trimEnd()
    }
}
