package com.kevinluo.autoglm.computer.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import com.kevinluo.autoglm.core.CommandResult

class CommandResultTest : FunSpec({
    test("nonzero shell exit is not successful input") {
        shouldThrow<IllegalStateException> {
            AndroidComputerDriver.validateCommandResult(CommandResult(137, "Killed", ""))
        }
        shouldThrow<IllegalStateException> {
            AndroidComputerDriver.validateCommandResult(CommandResult(1, "", ""))
        }
        AndroidComputerDriver.validateCommandResult(CommandResult(0, "Error is data, not an exit status", ""))
        shouldThrow<IllegalStateException> {
            AndroidComputerDriver.validateCommandResult(CommandResult(0, "", "", outputTruncated = true))
        }
        shouldThrow<IllegalStateException> { AndroidComputerDriver.validateCommandResult(CommandResult(null, "", "")) }
        shouldThrow<IllegalStateException> { AndroidComputerDriver.validateCommandResult(CommandResult(0, "", "", timedOut = true)) }
    }
})
