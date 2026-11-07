package com.kevinluo.autoglm.device

import com.kevinluo.autoglm.core.CommandResult

/** Preserves typed failure evidence for history without exposing stdout/field contents. */
internal class DeviceCommandFailure(val operation: String, val result: CommandResult) : IllegalStateException(
    diagnostic(operation, result),
) {
    companion object {
        private fun diagnostic(operation: String, result: CommandResult): String {
            require(operation.matches(Regex("[A-Za-z0-9 _-]{1,64}")))
            return "$operation failed: exitCode=${result.exitCode}, timedOut=${result.timedOut}, " +
                "outputTruncated=${result.outputTruncated}" +
                result.stderr.take(1000).takeIf { it.isNotBlank() }?.let { "; stderr=$it" }.orEmpty()
        }
    }
}

internal fun CommandResult.requireSuccessful(operation: String) {
    if (!succeeded) throw DeviceCommandFailure(operation, this)
}
