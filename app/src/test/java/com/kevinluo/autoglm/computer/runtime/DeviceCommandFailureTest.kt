package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.core.CommandResult
import com.kevinluo.autoglm.device.DeviceCommandFailure
import com.kevinluo.autoglm.device.requireSuccessful
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceCommandFailureTest {
    @Test fun `typed failure keeps flags and bounded stderr while excluding stdout`() {
        val result = CommandResult(137, "private field contents", "e".repeat(2000), timedOut = true, outputTruncated = true)
        val error = assertThrows(DeviceCommandFailure::class.java) { result.requireSuccessful("tap") }
        assertSame(result, error.result)
        assertTrue(error is IllegalStateException)
        assertEquals("tap", error.operation)
        assertTrue(error.message!!.contains("exitCode=137, timedOut=true, outputTruncated=true"))
        assertEquals("e".repeat(1000), error.message!!.substringAfter("stderr="))
        assertFalse(error.message!!.contains("private field contents"))
    }

    @Test fun `driver exposes structured failure evidence and treats successful stdout as data`() {
        val error = assertThrows(DeviceCommandFailure::class.java) {
            AndroidComputerDriver.validateCommandResult(CommandResult(null, "", "binder disconnected"))
        }
        assertTrue(error.message!!.contains("exitCode=null"))
        assertTrue(error.message!!.contains("binder disconnected"))
        AndroidComputerDriver.validateCommandResult(CommandResult(0, "Error is plain data"))
    }
}
