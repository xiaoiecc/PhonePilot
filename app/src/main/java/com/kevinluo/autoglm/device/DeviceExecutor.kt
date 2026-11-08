package com.kevinluo.autoglm.device

import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.core.CommandResult
import com.kevinluo.autoglm.display.DisplaySessionClient
import com.kevinluo.autoglm.util.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Typed device primitives. Model-provided strings never become shell syntax. */
class DeviceExecutor(userService: IUserService, val displays: DisplaySessionClient = DisplaySessionClient(userService)) {
    private val commands = DeviceCommandClient(userService)
    private var cachedTouchDevice: String? = null

    suspend fun straightSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): CommandResult {
        require(listOf(startX, startY, endX, endY).all { it in 0..1000000 })
        require(durationMs in 1..5000)
        if (displays.state.runId.isNotEmpty()) return displays.input(DisplaySessionClient.SWIPE, startX, startY, endX, endY, durationMs)
        return commands.executeArgs(listOf("input", "swipe", "$startX", "$startY", "$endX", "$endY", "$durationMs"))
    }

    suspend fun tap(x: Int, y: Int): CommandResult {
        require(x in 0..1000000 && y in 0..1000000)
        if (displays.state.runId.isNotEmpty()) return displays.input(DisplaySessionClient.TAP, x, y)
        return commands.executeArgs(listOf("input", "tap", "$x", "$y"))
    }

    suspend fun doubleTap(x: Int, y: Int): CommandResult {
        val first = tap(x, y)
        if (!first.succeeded) return first
        delay(DOUBLE_TAP_INTERVAL_MS)
        val second = tap(x, y)
        return if (!second.succeeded) second else second.copy(stdout = first.stdout + second.stdout)
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Int = DEFAULT_LONG_PRESS_DURATION_MS): CommandResult =
        straightSwipe(x, y, x, y, durationMs)

    suspend fun swipe(points: List<Point>, durationMs: Int): CommandResult = withContext(Dispatchers.IO) {
        require(points.size in 2..1000)
        require(points.all { it.x in 0..1000000 && it.y in 0..1000000 })
        require(durationMs in 1..5000)
        // Physical sendevent has no display routing. An attached session always uses display-bound input.
        if (displays.state.runId.isNotEmpty()) {
            return@withContext straightSwipe(points.first().x, points.first().y, points.last().x, points.last().y, durationMs)
        }
        val device = getTouchDevicePath()
        if (device == null) {
            val start = points.first()
            val end = points.last()
            return@withContext straightSwipe(start.x, start.y, end.x, end.y, durationMs)
        }
        // The script is private and only interpolates validated integers and an eventN path.
        // Once a curved gesture starts, failures must not fall back and replay the gesture.
        val script = buildCurvedSwipeScript(device, points, durationMs)
        commands.executeArgs(listOf("sh", "-c", script), timeoutMs = 10000)
    }

    private suspend fun getTouchDevicePath(): String? {
        cachedTouchDevice?.let { return it }
        val result = commands.executeArgs(listOf("getevent", "-pl"), timeoutMs = 5000)
        if (!result.succeeded) return null
        var candidate: String? = null
        result.stdout.lineSequence().forEach { line ->
            Regex("^\\s*add device \\d+: (/dev/input/event\\d+)\\s*$").find(line)?.let { candidate = it.groupValues[1] }
            if (line.contains("ABS_MT_POSITION") && candidate != null) {
                cachedTouchDevice = candidate
                return candidate
            }
        }
        return null
    }

    private fun buildCurvedSwipeScript(device: String, points: List<Point>, durationMs: Int): String {
        require(device.matches(Regex("/dev/input/event\\d+")))
        val pauseUs = durationMs * 1000L / (points.size - 1)
        return buildString {
            appendLine("set -e")
            appendLine("trap 'sendevent $device 3 57 -1; sendevent $device 0 0 0' EXIT")
            appendLine("sendevent $device 3 57 0")
            points.forEachIndexed { index, point ->
                if (index > 0) appendLine("usleep $pauseUs")
                appendLine("sendevent $device 3 53 ${point.x}")
                appendLine("sendevent $device 3 54 ${point.y}")
                if (index == 0) {
                    appendLine("sendevent $device 3 58 50")
                    appendLine("sendevent $device 3 48 5")
                }
                appendLine("sendevent $device 0 0 0")
            }
        }
    }

    suspend fun pressKey(keyCode: Int): CommandResult {
        require(keyCode in 0..1000)
        if (displays.state.runId.isNotEmpty()) return displays.input(DisplaySessionClient.KEY, keyCode)
        return commands.executeArgs(listOf("input", "keyevent", "$keyCode"))
    }

    suspend fun launchApp(packageName: String): CommandResult {
        if (!isPackageName(packageName)) return rejected("Invalid Android package")
        val resolved = commands.executeArgs(listOf("pm", "resolve-activity", "--brief", "-a",
            "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageName))
        if (!resolved.succeeded) return resolved
        val lines = resolved.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val component = lines.firstOrNull { '/' in it }
        if (component != null) {
            if (!isComponentName(packageName, component)) return rejected("Invalid Android launcher component")
            if (displays.state.runId.isNotEmpty()) return displays.launch(component)
            return commands.executeArgs(listOf("am", "start", "-n", component))
        }
        if (displays.state.runId.isNotEmpty()) return rejected("No verified launcher component for the target display")
        return commands.executeArgs(listOf("am", "start", "-a", "android.intent.action.MAIN", "-c",
            "android.intent.category.LAUNCHER", "-p", packageName))
    }

    suspend fun getCurrentApp(): String {
        displays.state.takeIf { it.runId.isNotEmpty() }?.let { current ->
            val fresh = displays.refresh(current.runId, current.generation)
            return fresh.packageName.takeIf { fresh.success }.orEmpty()
        }
        val result = commands.executeArgs(listOf("dumpsys", "window"))
        return if (result.succeeded) parseCurrentApp(result.stdout) else ""
    }

    internal fun parseCurrentApp(dumpsysOutput: String): String {
        val patterns = listOf(
            Regex("""mCurrentFocus=Window\{[^}]*\s+([a-zA-Z0-9_.]+)/"""),
            Regex("""mFocusedApp=.*ActivityRecord\{[^}]*\s+([a-zA-Z0-9_.]+)/"""),
            Regex("""mFocusedApp=.*\s+([a-zA-Z0-9_.]+)/"""),
        )
        return patterns.firstNotNullOfOrNull { it.find(dumpsysOutput)?.groupValues?.get(1) }.orEmpty()
    }

    companion object {
        internal fun isPackageName(value: String) = value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
        internal fun isComponentName(packageName: String, value: String): Boolean {
            val parts = value.split('/')
            return parts.size == 2 && parts[0] == packageName && isPackageName(parts[0]) &&
                parts[1].removePrefix(".").split('.').all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) }
        }
        private fun rejected(reason: String) = CommandResult(null, "", reason)
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val DOUBLE_TAP_INTERVAL_MS = 100L
        const val DEFAULT_LONG_PRESS_DURATION_MS = 3000
    }
}
