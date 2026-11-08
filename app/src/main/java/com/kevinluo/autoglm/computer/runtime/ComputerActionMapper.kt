package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.util.Point
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Validated, physical-pixel operations. Never pass these through the legacy 0..999 converter. */
sealed interface PreparedAction {
    data class Tap(val point: Point, val doubleTap: Boolean = false) : PreparedAction
    data class Swipe(val from: Point, val to: Point) : PreparedAction
    data class Scroll(val gestures: List<Swipe>) : PreparedAction
    data class Type(val text: String) : PreparedAction
    data class Key(val code: Int) : PreparedAction
    data object Wait : PreparedAction
    data object ScreenshotOnly : PreparedAction
}

object ComputerActionMapper {
    fun validateFrame(frame: Screenshot) {
        require(!frame.isSensitive && frame.base64Data.isNotBlank()) { "无法读取当前屏幕，请手动处理锁屏或受保护页面后重试" }
        require(frame.width > 1 && frame.height > 1 && frame.originalWidth > 1 && frame.originalHeight > 1) {
            "Invalid screenshot dimensions"
        }
    }

    fun point(x: Int, y: Int, frame: Screenshot): Point {
        validateFrame(frame)
        require(x in 0 until frame.width && y in 0 until frame.height) { "Computer coordinates outside the screenshot" }
        return Point(
            (x.toDouble() * frame.originalWidth / frame.width).roundToInt().coerceAtMost(frame.originalWidth - 1),
            (y.toDouble() * frame.originalHeight / frame.height).roundToInt().coerceAtMost(frame.originalHeight - 1),
        )
    }

    fun prepare(action: JsonObject, frame: Screenshot): PreparedAction {
        validateFrame(frame)
        val actionType = action.string("type")
        if (actionType != "keypress" && action["keys"] != null && action["keys"] != JsonNull) {
            val modifiers = action["keys"] as? JsonArray ?: error("Invalid mouse modifiers")
            require(modifiers.isEmpty()) { "Modified mouse actions are unsupported on Android" }
        }
        fun at() = point(action.integer("x"), action.integer("y"), frame)
        return when (actionType) {
            "click" -> {
                val button = action.string("button")
                require(button in setOf("left", "wheel", "back", "forward")) { "Unsupported computer button" }
                val target = at()
                when (button) {
                    "back" -> PreparedAction.Key(4)
                    "forward" -> PreparedAction.Key(125) // KEYCODE_FORWARD; 6 is ENDCALL.
                    "wheel" -> error("Middle mouse clicks are unsupported on a touchscreen")
                    else -> PreparedAction.Tap(target)
                }
            }
            "double_click" -> PreparedAction.Tap(at(), doubleTap = true)
            "drag" -> {
                val path = action["path"] as? JsonArray ?: error("Drag path is required")
                require(path.size in 2..100) { "Drag requires 2–100 points" }
                val points = path.map {
                    val entry = it as? JsonObject ?: error("Invalid drag point")
                    point(entry.integer("x"), entry.integer("y"), frame)
                }
                val first = points.first()
                val last = points.last()
                val dx = last.x.toLong() - first.x
                val dy = last.y.toLong() - first.y
                require(dx != 0L || dy != 0L) { "A drag needs distinct endpoints" }
                val distanceSquared = dx * dx + dy * dy
                require(points.all {
                    val px = it.x.toLong() - first.x
                    val py = it.y.toLong() - first.y
                    val cross = px * dy - py * dx
                    val dot = px * dx + py * dy
                    cross * cross <= 4L * distanceSquared && dot in 0..distanceSquared
                }) { "Curved drag paths are not supported on this Android driver" }
                val projections = points.map { (it.x.toLong() - first.x) * dx + (it.y.toLong() - first.y) * dy }
                require(projections.zipWithNext().all { (a, b) -> b >= a }) {
                    "Backtracking drag paths are unsupported; request a straight drag"
                }
                PreparedAction.Swipe(first, last)
            }
            "scroll" -> {
                at() // The model-supplied scroll target must be on screen.
                val dx = action.integer("scroll_x")
                val dy = action.integer("scroll_y")
                require(abs(dx.toLong()) <= 10000 && abs(dy.toLong()) <= 10000) { "Scroll distance is too large" }
                require(dx == 0 || dy == 0) { "Diagonal scroll is unsupported; use one axis at a time" }
                val gestures = mutableListOf<PreparedAction.Swipe>()
                val vertical = dy != 0
                var remaining = if (vertical) dy else dx
                val x = action.integer("x")
                val y = action.integer("y")
                // Keep every gesture in the same scrollable region around the supplied target.
                val before = if (vertical) y else x
                val after = (if (vertical) frame.height - 1 - y else frame.width - 1 - x)
                val capacity = 2 * minOf(before, after)
                require(remaining == 0 || capacity >= 2) { "Scroll target is too close to the screen edge" }
                while (remaining != 0) {
                    require(gestures.size < 20) { "Scroll requires too many gestures; request a smaller scroll" }
                    val amount = remaining.coerceIn(-capacity, capacity)
                    val half = amount / 2
                    val from = if (vertical) point(x, y + half, frame) else point(x + half, y, frame)
                    val to = if (vertical) point(x, y - (amount - half), frame) else point(x - (amount - half), y, frame)
                    gestures.add(PreparedAction.Swipe(from, to))
                    remaining -= amount
                }
                PreparedAction.Scroll(gestures)
            }
            "type" -> PreparedAction.Type(action.string("text").also {
                require(it.length <= 20000) { "Text input is too long" }
            })
            "keypress" -> {
                val keys = action["keys"] as? JsonArray ?: error("Keypress keys are required")
                require(keys.size == 1) { "Keyboard chords are unsupported on Android" }
                val key = (keys.single() as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Invalid key")
                PreparedAction.Key(keyCode(key))
            }
            "wait" -> PreparedAction.Wait
            "screenshot" -> PreparedAction.ScreenshotOnly
            "move" -> error("Mouse hover is unsupported on a touchscreen")
            else -> error("Unsupported computer action")
        }
    }

    private fun keyCode(value: String): Int = when (val key = value.uppercase()) {
        "BACK", "BROWSERBACK" -> 4
        "HOME" -> 3
        "ENTER", "RETURN" -> 66
        "BACKSPACE" -> 67
        "DELETE" -> 112
        "TAB" -> 61
        "ESC", "ESCAPE" -> 111
        "SPACE" -> 62
        "UP", "ARROWUP" -> 19
        "DOWN", "ARROWDOWN" -> 20
        "LEFT", "ARROWLEFT" -> 21
        "RIGHT", "ARROWRIGHT" -> 22
        else -> when {
            key.length == 1 && key[0] in '0'..'9' -> 7 + (key[0] - '0')
            key.length == 1 && key[0] in 'A'..'Z' -> 29 + (key[0] - 'A')
            else -> error("Unsupported Android key")
        }
    }

    private fun JsonObject.integer(name: String): Int =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: error("Missing or invalid $name")

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Missing or invalid $name")
}
