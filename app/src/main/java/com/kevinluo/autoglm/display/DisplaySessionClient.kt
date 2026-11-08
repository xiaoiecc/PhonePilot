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
    suspend fun attach(runId: String): DisplaySessionState = call { service.attachDisplay(runId) }
    suspend fun refresh(runId: String, generation: Long): DisplaySessionState = call { service.inspectDisplay(runId, generation) }
    suspend fun switchMode(runId: String, generation: Long, background: Boolean): DisplaySessionState =
        call { service.switchDisplay(runId, generation, background) }
    suspend fun close(runId: String, generation: Long): DisplaySessionState = call { service.closeDisplay(runId, generation) }
    suspend fun capture(runId: String, generation: Long, imageId: String): String = withContext(Dispatchers.IO) {
        captureBlocking(runId, generation, imageId)
    }
    /** Called only by the existing bounded screenshot Binder gate. */
    internal fun captureBlocking(runId: String, generation: Long, imageId: String): String {
        val receipt = service.captureDisplay(runId, generation, imageId)
        check(receipt.success) { receipt.message }
        check(receipt.imagePath.matches(Regex("/data/local/tmp/screenshot_[a-f0-9]{32}\\.png"))) { "Invalid screenshot receipt" }
        return receipt.imagePath
    }
    internal suspend fun input(operation: Int, vararg values: Int): CommandResult = withContext(Dispatchers.IO) {
        val target = state
        if (target.runId.isEmpty()) CommandResult(null, stderr = "No display session is attached")
        else service.inputDisplay(target.runId, target.generation, operation, values).toResult()
    }
    internal suspend fun launch(component: String): CommandResult = withContext(Dispatchers.IO) {
        val target = state
        service.launchOnDisplay(target.runId, target.generation, component).toResult()
    }
    private suspend fun call(block: () -> DisplayResultParcel): DisplaySessionState = withContext(Dispatchers.IO) {
        val result = block()
        DisplaySessionState(result.runId, result.generation, result.displayId, result.taskId, result.packageName,
            result.width, result.height, result.density, result.background, result.success, result.message).also {
                if (it.generation >= state.generation) state = it
            }
    }
    companion object { const val TAP = 1; const val SWIPE = 2; const val KEY = 3 }
}
