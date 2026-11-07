package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.computer.protocol.ComputerResponse
import com.kevinluo.autoglm.history.*
import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Observational metadata only; never a second executable representation. */
internal object TraceActionMapper {
    fun normalized(value: Int, dimension: Int): Int = (value.toDouble() * 999 / (dimension - 1)).roundToInt().coerceIn(0, 999)
    fun action(action: PreparedAction, frame: Screenshot?): AgentAction? {
        fun x(value: Int) = normalized(value, requireNotNull(frame).originalWidth)
        fun y(value: Int) = normalized(value, requireNotNull(frame).originalHeight)
        return when (action) {
            is PreparedAction.Tap -> when {
                action.longPress -> AgentAction.LongPress(x(action.point.x), y(action.point.y), 650)
                action.doubleTap -> AgentAction.DoubleTap(x(action.point.x), y(action.point.y))
                else -> AgentAction.Tap(x(action.point.x), y(action.point.y))
            }
            is PreparedAction.Swipe -> AgentAction.Swipe(x(action.from.x), y(action.from.y), x(action.to.x), y(action.to.y), false)
            is PreparedAction.Scroll -> error("Scroll primitives must be traced individually")
            is PreparedAction.Type -> AgentAction.Type(action.text)
            is PreparedAction.Key -> when (action.code) {
                4 -> AgentAction.Back; 3 -> AgentAction.Home; 24 -> AgentAction.VolumeUp; 25 -> AgentAction.VolumeDown; 26 -> AgentAction.Power
                else -> AgentAction.Note("Android keycode ${action.code}")
            }
            PreparedAction.Wait -> AgentAction.Wait(2f)
            PreparedAction.ScreenshotOnly -> null
        }
    }
}

internal class ToolTraceBatch(response: ComputerResponse, private val runId: String, private val step: Int,
    includeHosted: Boolean = false) {
    private val json = Json { prettyPrint = true }
    private val entries = response.outputOrder.associateWith { id ->
        val function = response.functionCalls.firstOrNull { it.callId == id }
        val arguments: JsonElement = function?.arguments ?: JsonArray(response.calls.first { it.callId == id }.actions)
        Entry(id, function?.name ?: "computer", json.encodeToString(JsonElement.serializer(), arguments))
    }.toMutableMap().apply {
        if (includeHosted) response.hostedCalls.forEach { call ->
            put(call.id, Entry(call.id, call.type, call.arguments).apply {
                status = if (call.status == "completed") HistoryToolStatus.SUCCEEDED else HistoryToolStatus.FAILED
                result = call.result
            })
        }
    }
    internal class Entry(val id: String, val name: String, val arguments: String) {
        var started = System.currentTimeMillis()
        var finished = started
        var status = HistoryToolStatus.SKIPPED
        var result = "Not executed: a newer instruction replaced this plan."
        val actions = mutableListOf<AgentAction>()
        var before: Screenshot? = null
        var after: Screenshot? = null
        fun failed(error: Throwable) {
            status = if (error is CancellationException) HistoryToolStatus.CANCELLED else HistoryToolStatus.FAILED
            result = error.message ?: error.javaClass.simpleName
            finished = System.currentTimeMillis()
        }
    }
    fun begin(id: String, frame: Screenshot?): Entry = entries.getValue(id).also {
        it.started = System.currentTimeMillis(); it.before = frame
        it.status = HistoryToolStatus.SUCCEEDED; it.result = "Completed"
    }
    fun reject(id: String, error: Throwable) { entries.getValue(id).failed(error) }
    fun abort(error: Throwable) {
        entries.values.filter { it.status == HistoryToolStatus.SKIPPED }.forEach {
            if (error is CancellationException) it.failed(error)
            else { it.result = "Not executed: batch stopped (${error.message.orEmpty()})"; it.finished = System.currentTimeMillis() }
        }
    }
    suspend fun persist(history: HistoryManager?) {
        if (history == null) return
        withContext(NonCancellable) {
            entries.values.forEach { entry ->
                val call = HistoryToolCall(entry.id, runId, step, entry.name, entry.arguments.take(16000),
                    entry.started, maxOf(entry.started, entry.finished), entry.status, entry.result.take(12000),
                    entry.actions.toList(), detailsTruncated = entry.arguments.length > 16000 || entry.result.length > 12000)
                // Failed persistence must never convert completed phone input into a retry.
                try { history.recordToolCall(call, entry.before, entry.after) }
                catch (_: Exception) { Logger.w("ToolTrace", "Unable to persist tool trace; completed input was not retried") }
            }
        }
    }
}
