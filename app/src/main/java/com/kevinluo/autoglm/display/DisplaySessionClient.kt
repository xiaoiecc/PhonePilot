package com.kevinluo.autoglm.display

import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.core.CommandResult
import com.kevinluo.autoglm.device.DisplayResultParcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DisplaySessionState(
    val runId: String = "", val generation: Long = 0, val displayId: Int = 0,
    val taskId: Int = -1, val packageName: String = "", val width: Int = 0, val height: Int = 0,
    val density: Int = 0, val background: Boolean = false, val success: Boolean = true,
    val message: String = "",
)

/** Resource transport/cache only; the run coordinator admits all mode transitions. */
class DisplaySessionClient(private val service: IUserService) {
    @Volatile var state = DisplaySessionState()
        private set
    suspend fun attach(runId: String): DisplaySessionState = call("attach", runId, 0) { service.attachDisplay(runId) }
    suspend fun refresh(runId: String, generation: Long): DisplaySessionState =
        call("refresh", runId, generation) { service.inspectDisplay(runId, generation) }
    suspend fun switchMode(runId: String, generation: Long, background: Boolean): DisplaySessionState =
        call("switch", runId, generation, background) { service.switchDisplay(runId, generation, background) }
    suspend fun close(runId: String, generation: Long): DisplaySessionState =
        call("close", runId, generation) { service.closeDisplay(runId, generation) }
    suspend fun capture(runId: String, generation: Long, imageId: String): String = withContext(Dispatchers.IO) {
        captureBlocking(runId, generation, imageId)
    }
    /** Called only by the existing bounded screenshot Binder gate. */
    internal fun captureBlocking(runId: String, generation: Long, imageId: String): String =
        com.kevinluo.autoglm.util.DiagnosticLog.trace("Display", "capture",
            "runId" to runId, "generation" to generation, "imageId" to imageId) {
            val receipt = service.captureDisplay(runId, generation, imageId)
            check(receipt.success) { receipt.message }
            check(receipt.imagePath.matches(Regex("/data/local/tmp/screenshot_[a-f0-9]{32}\\.png"))) { "Invalid screenshot receipt" }
            receipt.imagePath
        }
    internal suspend fun input(operation: Int, vararg values: Int): CommandResult = withContext(Dispatchers.IO) {
        val target = state
        com.kevinluo.autoglm.util.DiagnosticLog.event("Display", "input.started",
            "runId" to target.runId, "generation" to target.generation,
            "operation" to operation, "values" to values)
        if (target.runId.isEmpty()) CommandResult(null, stderr = "No display session is attached")
        else service.inputDisplay(target.runId, target.generation, operation, values).toResult().also { result ->
            com.kevinluo.autoglm.util.DiagnosticLog.event("Display", "input.completed",
                "exitCode" to result.exitCode, "timedOut" to result.timedOut,
                "outputTruncated" to result.outputTruncated)
        }
    }
    internal suspend fun launch(component: String): CommandResult = withContext(Dispatchers.IO) {
        val target = state
        com.kevinluo.autoglm.util.DiagnosticLog.event("Display", "launch.started",
            "runId" to target.runId, "generation" to target.generation, "component" to component)
        val launched = service.launchOnDisplay(target.runId, target.generation, component).toResult()
        if (!launched.succeeded) return@withContext launched
        val refreshed = call("launch.refresh", target.runId, target.generation) {
            service.inspectDisplay(target.runId, target.generation)
        }
        if (!refreshed.success || refreshed.packageName != component.substringBefore('/') ||
            refreshed.background != target.background) {
            return@withContext CommandResult(null, stderr = "启动已受理，但实际目标尚未确认；请重新观察，不要重复启动")
        }
        com.kevinluo.autoglm.util.DiagnosticLog.event("Display", "launch.completed",
            "exitCode" to launched.exitCode, "timedOut" to launched.timedOut,
            "packageName" to refreshed.packageName, "taskId" to refreshed.taskId,
            "generation" to refreshed.generation)
        launched
    }
    private suspend fun call(
        operation: String, runId: String, generation: Long, background: Boolean? = null,
        block: () -> DisplayResultParcel,
    ): DisplaySessionState = com.kevinluo.autoglm.util.DiagnosticLog.traceSuspend("Display", operation,
        "runId" to runId, "generation" to generation, "requestedBackground" to background,
        "stateBefore" to state) {
        kotlinx.coroutines.withTimeout(5000) {
            withContext(Dispatchers.IO) {
                val result = block()
                DisplaySessionState(result.runId, result.generation, result.displayId, result.taskId, result.packageName,
                    result.width, result.height, result.density, result.background, result.success, result.message).also {
                        if (it.generation >= state.generation) state = it
                        com.kevinluo.autoglm.util.DiagnosticLog.event("Display", "state",
                            "operation" to operation, "runId" to it.runId, "generation" to it.generation,
                            "displayId" to it.displayId, "taskId" to it.taskId, "packageName" to it.packageName,
                            "width" to it.width, "height" to it.height, "density" to it.density,
                            "background" to it.background, "success" to it.success,
                            "messageLength" to it.message.length)
                    }
            }
        }
    }
    companion object { const val TAP = 1; const val SWIPE = 2; const val KEY = 3 }
}
