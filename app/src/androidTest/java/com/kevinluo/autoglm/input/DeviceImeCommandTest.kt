package com.kevinluo.autoglm.input

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinluo.autoglm.ComponentManager
import com.kevinluo.autoglm.IUserService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class DeviceImeCommandTest {
    @Test
    fun imeEnableUsesTheRealShizukuCommandChannel() = runBlocking {
        assertTrue("Shizuku is not running", Shizuku.pingBinder())
        assertEquals(PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val components = ComponentManager.getInstance(context)
        val service = withTimeout(10000) {
            var current: IUserService? = null
            while (current == null) {
                current = components.userService
                if (current == null) delay(100)
            }
            current
        }
        fun execute(argv: Array<String>) = service.executeArgs(argv, 5000, 16000)
        val originalResult = execute(arrayOf("settings", "get", "secure", "default_input_method"))
        val originalIme = originalResult.stdout.trim()
        assertEquals("Unable to read original IME: ${originalResult.stderr}", 0, originalResult.exitCode)
        assertTrue(KeyboardHelper.isValidImeId(originalIme))
        try {
            val enabled = execute(arrayOf("ime", "enable", KeyboardHelper.IME_ID))
            assertEquals("ime enable failed: ${enabled.stderr}", 0, enabled.exitCode)
            val switched = execute(arrayOf("ime", "set", KeyboardHelper.IME_ID))
            assertEquals("ime set failed: ${switched.stderr}", 0, switched.exitCode)
            val selected = execute(arrayOf("settings", "get", "secure", "default_input_method"))
            assertTrue("AutoGLM Keyboard was not selected: ${selected.stdout}", KeyboardHelper.isAutoGLMKeyboard(selected.stdout.trim()))
        } finally {
            val restored = execute(arrayOf("ime", "set", originalIme))
            assertEquals("Failed to restore original IME: ${restored.stderr}", 0, restored.exitCode)
        }
    }
}
