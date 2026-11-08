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
        override fun isVisible() = attached
        override fun hide() { attached = false }
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

    @Test fun `read only accessibility observation does not hide the overlay`() = runTest {
        val service = mockk<PhoneAccessibilityService>()
        val window = Window()
        coEvery { service.execute(any(), any()) } answers {
            assertEquals(true, window.attached)
            "ok"
        }
        val harness = RunnerHarness(this, service = service, window = window)
        harness.respond(
            functionResponse("r1", function("observe", "android_ui", """{"operation":"observe"}""")),
            finalResponse(),
        )
        harness.run()
        assertTrue(window.attached)
    }
}
