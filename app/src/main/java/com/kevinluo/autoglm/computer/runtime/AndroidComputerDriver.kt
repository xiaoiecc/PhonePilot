package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.device.requireSuccessful
import com.kevinluo.autoglm.core.CommandResult
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.screenshot.FloatingWindowController
import com.kevinluo.autoglm.screenshot.withOverlayHidden
import kotlinx.coroutines.delay

class AndroidComputerDriver(
    private val deviceExecutor: DeviceExecutor,
    private val textInputManager: TextInputManager,
    private val floatingWindowProvider: (() -> FloatingWindowController?)? = null,
) {
    /** Pause/cancel checkpoints occur between primitive gestures, never replaying completed input. */
    suspend fun execute(action: PreparedAction, checkpoint: suspend () -> Unit = {},
        onAcknowledged: (PreparedAction) -> Unit = {}) {
        checkpoint()
        // ScreenshotService owns the single hide/capture/restore lease. This placeholder
        // must not detach the overlay (and clear editor focus) once before the real capture.
        if (action == PreparedAction.ScreenshotOnly) return
        if (action == PreparedAction.Wait) {
            error("Wait actions must use the run coordinator")
        }
        val window = floatingWindowProvider?.invoke()
        if (action is PreparedAction.Scroll) {
            action.gestures.forEach { gesture ->
                withOverlayHidden(window, checkpoint) { swipe(gesture); onAcknowledged(gesture) }
                delay(150)
            }
            return
        }
        withOverlayHidden(window, checkpoint) {
            when (action) {
                is PreparedAction.Tap -> checkResult(when {
                    action.doubleTap -> deviceExecutor.doubleTap(action.point.x, action.point.y)
                    else -> deviceExecutor.tap(action.point.x, action.point.y)
                })
                is PreparedAction.Swipe -> swipe(action)
                is PreparedAction.Scroll -> Unit // Handled one primitive at a time above.
                is PreparedAction.Type -> {
                    val result = textInputManager.appendText(action.text)
                    check(result.success) { result.message }
                }
                is PreparedAction.Key -> checkResult(deviceExecutor.pressKey(action.code))
                PreparedAction.Wait -> Unit
                PreparedAction.ScreenshotOnly -> Unit
            }
            // Synchronous receipt observation before coroutineScope/overlay restoration can
            // propagate cancellation. This callback never owns execution or lifecycle state.
            onAcknowledged(action)
        }
    }

    private suspend fun swipe(action: PreparedAction.Swipe) {
        checkResult(deviceExecutor.straightSwipe(action.from.x, action.from.y, action.to.x, action.to.y, 400))
    }

    private fun checkResult(result: CommandResult) {
        validateCommandResult(result)
    }

    companion object {
      internal fun validateCommandResult(result: CommandResult) {
        result.requireSuccessful("android_input")
      }
    }
}
