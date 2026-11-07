package com.kevinluo.autoglm.screenshot

import com.kevinluo.autoglm.core.CommandResult

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenshotCommandGateTest {
    @Test fun `partial image command output is rejected on timeout or missing status`() {
        requireSuccessfulScreenshotCommand(CommandResult(0, "base64DataContainingErrorIsStillData", ""))
        assertThrows<ScreenshotCaptureException> { requireSuccessfulScreenshotCommand(CommandResult(124, "partial image", "", timedOut = true)) }
        assertThrows<ScreenshotCaptureException> { requireSuccessfulScreenshotCommand(CommandResult(null, "partial image", "")) }
        assertThrows<ScreenshotCaptureException> { requireSuccessfulScreenshotCommand(CommandResult(0, "partial image", "", outputTruncated = true)) }
    }

    private class Window : FloatingWindowController {
        var visible = true
        override fun isVisible() = visible
        override fun hide() { visible = false }
        override fun show() { visible = true }
        override fun showAndBringToFront() = show()
    }

    @Test fun `stuck synchronous screenshot restores controls at deadline and late output cannot resume the task`() = runBlocking<Unit> {
        val gate = ScreenshotCommandGate()
        val release = CountDownLatch(1)
        val window = Window()
        var nextInputExecuted = false
        try {
            assertThrows<ScreenshotCaptureException> {
                runBlocking {
                    withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                        gate.execute(100) { release.await(5, TimeUnit.SECONDS); "late screenshot" }
                    }
                    nextInputExecuted = true
                }
            }
            assertTrue(window.visible)
            assertTrue(gate.isBusy)
            assertFalse(nextInputExecuted)
            val retries = AtomicInteger()
            assertThrows<ScreenshotCaptureException> { runBlocking { gate.execute(100) { retries.incrementAndGet(); "retry" } } }
            assertEquals(0, retries.get(), "A timed out Binder must not spawn replacement calls")
        } finally { release.countDown() }
        withTimeout(2000) { while (gate.isBusy) delay(5) }
        assertFalse(nextInputExecuted)
        assertEquals("new screenshot", gate.execute(2000) { "new screenshot" })
    }

    @Test fun `user cancellation restores overlay without waiting for the uninterruptible call`() = runBlocking<Unit> {
        val gate = ScreenshotCommandGate()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val window = Window()
        val job = launch {
            withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
                gate.execute(10000) { started.complete(Unit); release.await(5, TimeUnit.SECONDS); "ignored" }
            }
        }
        try {
            withTimeout(2000) { started.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(window.visible)
            assertTrue(gate.isBusy)
        } finally { release.countDown() }
        withTimeout(2000) { while (gate.isBusy) delay(5) }
    }

    @Test fun `failed command and rejected executor release single flight ownership`() = runBlocking<Unit> {
        val gate = ScreenshotCommandGate()
        assertThrows<IllegalStateException> { runBlocking { gate.execute(2000) { error("remote failed") } } }
        assertFalse(gate.isBusy)
        assertEquals("ok", gate.execute(2000) { "ok" })
        val rejected = ScreenshotCommandGate { throw java.util.concurrent.RejectedExecutionException("closed") }
        assertThrows<java.util.concurrent.RejectedExecutionException> { runBlocking { rejected.execute(2000) { "unused" } } }
        assertFalse(rejected.isBusy)
    }

    @Test fun `image post processing happens after overlay restoration`() = runBlocking<Unit> {
        val gate = ScreenshotCommandGate()
        val window = Window()
        val pixels = withOverlayHidden(window, hideDelayMs = 0, uiDispatcher = Dispatchers.Unconfined) {
            gate.execute(2000) { "pixels" }
        }
        assertTrue(window.visible)
        assertEquals("pixels", pixels)
        // Subsequent read/compress/save work has no hidden-overlay lease.
        assertEquals("compressed", gate.execute(2000) { "compressed" })
        assertTrue(window.visible)
    }
}
