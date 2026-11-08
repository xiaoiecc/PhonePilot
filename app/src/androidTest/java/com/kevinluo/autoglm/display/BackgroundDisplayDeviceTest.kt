package com.kevinluo.autoglm.display

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import android.content.Intent
import com.kevinluo.autoglm.ComponentManager
import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.accessibility.UiCommand
import com.kevinluo.autoglm.accessibility.NodeAction
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.ui.FloatingWindowService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit calculator-only device acceptance; no model, account operation, or conversation creation. */
@RunWith(AndroidJUnit4::class)
class BackgroundDisplayDeviceTest {
    @Test fun calculatorSurvivesTwoRoundTripsAndBackgroundInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun report(stage: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("background_stage", stage) })
        report("connecting")
        val components = ComponentManager.getInstance(instrumentation.targetContext)
        // EMUI freezes background instrumentation without the same foreground-service
        // lifetime that a real run owns. Never work around it with global freezer settings.
        val serviceWasRunning = FloatingWindowService.getInstance() != null
        instrumentation.runOnMainSync {
            ContextCompat.startForegroundService(instrumentation.targetContext,
                Intent(instrumentation.targetContext, FloatingWindowService::class.java))
        }
        withTimeout(5000) { while (FloatingWindowService.getInstance() == null) delay(50) }
        val service: IUserService = withTimeout(12000) {
            while (components.userService == null) delay(100)
            checkNotNull(components.userService)
        }
        val launched = service.executeArgs(arrayOf("am", "start", "-W", "-n", "com.huawei.calculator/.Calculator"), 8000, 16000)
        assertEquals("Calculator launch failed", 0, launched.exitCode)
        delay(1200)
        val displays = DisplaySessionClient(service)
        val device = DeviceExecutor(service, displays)
        val abandoned = displays.attach("test-" + UUID.randomUUID().toString())
        if (!abandoned.success && abandoned.runId.startsWith("test-") && !abandoned.background) {
            assertTrue(displays.close(abandoned.runId, abandoned.generation).success)
        }
        var state = if (displays.state.runId.isEmpty()) displays.attach("test-" + UUID.randomUUID().toString()) else abandoned
        try {
            assertTrue("attach: ${state.message}", state.success)
            assertEquals("com.huawei.calculator", state.packageName)
            val originalTask = state.taskId
            repeat(2) {
                report("to_background_$it")
                state = displays.switchMode(state.runId, state.generation, true)
                assertTrue("to background: ${state.message}", state.success)
                assertTrue(state.displayId > 0)
                assertEquals(originalTask, state.taskId)
                delay(600)
                val screenshots = ScreenshotService(service, backgroundCapture = { id ->
                    displays.captureBlocking(state.runId, state.generation, id)
                })
                report("capture_$it")
                val image = screenshots.capture()
                assertFalse("Background screenshot unavailable", image.isSensitive)
                assertEquals(state.width, image.originalWidth)
                assertEquals(state.height, image.originalHeight)
                report("tap_swipe_$it")
                val tapped = device.tap(state.width / 2, state.height * 3 / 4)
                assertTrue("Background tap rejected: ${tapped.stderr}", tapped.succeeded)
                val swiped = device.straightSwipe(state.width / 2, state.height / 2,
                    state.width / 2, state.height / 2 - 80, 180)
                assertTrue("Background swipe rejected: ${swiped.stderr}", swiped.succeeded)
                val ax = withTimeoutOrNull(20000) {
                    while (PhoneAccessibilityService.instance == null) delay(100)
                    checkNotNull(PhoneAccessibilityService.instance)
                }
                report("ax_$it")
                if (ax != null) {
                    ax.selectDisplay(state.displayId)
                    val tree = ax.observe()
                    if (tree.nodes.isNotEmpty()) {
                        val targetWindows = withContext(Dispatchers.Main) { ax.windowsForDisplay(state.displayId).map { it.id } }
                        assertTrue("Observed nodes must belong to this background display", tree.windowId in targetWindows)
                    }
                    val digit = tree.nodes.firstOrNull { it.label == "1" && it.actions.any { action -> action.id == "click" } }
                    if (digit != null) {
                        val result = ax.execute(UiCommand.Target(tree.id, digit.id, NodeAction.Simple.CLICK))
                        assertFalse("Background AX digit click: $result", result.startsWith("ERROR:"))
                    }
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("background_ax", if (tree.nodes.isEmpty()) "unavailable" else "nodes=${tree.nodes.size};digit_click=${digit != null}")
                    })
                }
                report("to_foreground_$it")
                state = displays.switchMode(state.runId, state.generation, false)
                assertTrue("to foreground: ${state.message}", state.success)
                assertEquals(0, state.displayId)
                assertEquals(originalTask, state.taskId)
                assertEquals("com.huawei.calculator", state.packageName)
                ax?.selectDisplay(0)
            }
            report("retain_background_task")
            state = displays.switchMode(state.runId, state.generation, true)
            assertTrue(state.success)
        } finally {
            report("cleanup")
            state = displays.state
            if (state.runId.startsWith("test-")) assertTrue("retain and release: ${displays.state.message}", displays.close(state.runId, state.generation).success)
            PhoneAccessibilityService.instance?.selectDisplay(0)
            if (!serviceWasRunning) instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, FloatingWindowService::class.java))
        }
    }
}
