package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.input.TextInputManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AndroidComputerDriverWaitTest {
    @Test
    fun `native screenshot placeholder never hides the overlay before the real capture`() = runBlocking<Unit> {
        val window = mockk<com.kevinluo.autoglm.screenshot.FloatingWindowController>(relaxed = true)
        val driver = AndroidComputerDriver(mockk(), mockk(), floatingWindowProvider = { window })
        driver.execute(PreparedAction.ScreenshotOnly)
        io.mockk.verify(exactly = 0) { window.hide() }
        io.mockk.verify(exactly = 0) { window.show() }
    }

    @Test
    fun `type preserves the actual keyboard gateway failure`() {
        val input = mockk<TextInputManager>()
        io.mockk.coEvery { input.appendText("abc") } returns com.kevinluo.autoglm.input.InputResult.failure("键盘未连接到输入框，未发送文字")
        val driver = AndroidComputerDriver(mockk(), input)
        val error = assertThrows<IllegalStateException> {
            runBlocking { driver.execute(PreparedAction.Type("abc")) }
        }
        org.junit.jupiter.api.Assertions.assertEquals("键盘未连接到输入框，未发送文字", error.message)
    }

    @Test
    fun `computer wait cannot bypass the coordinator through the device driver`() {
        val driver = AndroidComputerDriver(
            mockk<DeviceExecutor>(), mockk<TextInputManager>(),
            floatingWindowProvider = { error("Wait must not hide the floating window") },
        )
        assertThrows<IllegalStateException> { runBlocking { driver.execute(PreparedAction.Wait) } }
    }
}
