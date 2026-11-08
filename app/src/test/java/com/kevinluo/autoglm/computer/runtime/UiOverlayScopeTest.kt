package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.screenshot.FloatingWindowController
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiOverlayScopeTest {
    private class Window : FloatingWindowController {
        var attached = true
        var onHide: (() -> Unit)? = null
        override fun isVisible() = attached
        override fun hide() { attached = false; onHide?.invoke() }
        override fun show() { attached = true }
        override fun showAndBringToFront() = show()
    }

    @Test fun `mutating accessibility operations hide and restore the actual overlay`() = runTest {
        val service = mockk<PhoneAccessibilityService>()
        val window = Window()
        coEvery { service.execute(any(), any()) } answers {
            assertEquals(false, window.attached)
            "ok"
        }
        val harness = RunnerHarness(this, service = service, window = window)
        harness.respond(
            functionResponse("r1", function("click", "android_ui",
                """{"operation":"click","snapshot_id":"s","node_id":"n"}""")),
            finalResponse(),
        )
        harness.run()
        assertTrue(window.attached)
    }

    @Test fun `placement request during overlay hide does not deadlock the admitted UI primitive`() = runTest {
        val service = mockk<PhoneAccessibilityService>()
        val window = Window()
        coEvery { service.execute(any(), any()) } returns "ok"
        val harness = RunnerHarness(this, service = service, window = window)
        window.onHide = {
            harness.coordinator.requestExecutionMode(harness.coordinator.snapshot.value.runId!!,
                com.kevinluo.autoglm.core.ExecutionMode.BACKGROUND)
        }
        harness.respond(
            functionResponse("r1", function("click", "android_ui",
                """{"operation":"click","snapshot_id":"s","node_id":"n"}""")),
            finalResponse(),
        )
        harness.run()
        assertTrue(window.attached)
    }
}
