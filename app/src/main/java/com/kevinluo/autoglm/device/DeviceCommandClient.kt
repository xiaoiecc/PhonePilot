package com.kevinluo.autoglm.device

import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.core.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The only app-side transport adapter; never interprets output as executable syntax. */
internal class DeviceCommandClient(private val service: IUserService) {
    suspend fun executeArgs(
        argv: List<String>, timeoutMs: Long = 30000, maxOutputBytes: Int = 480000,
    ): CommandResult = withContext(Dispatchers.IO) { executeArgsBlocking(argv, timeoutMs, maxOutputBytes) }

    fun executeArgsBlocking(
        argv: List<String>, timeoutMs: Long = 30000, maxOutputBytes: Int = 480000,
    ): CommandResult {
        require(argv.isNotEmpty() && argv.size <= 4096 && argv.none { '\u0000' in it }) { "Invalid command arguments" }
        require(argv.sumOf { it.length.toLong() } <= 200000) { "Command arguments exceed Binder budget" }
        require(timeoutMs in 100..120000 && maxOutputBytes in 1..480000)
        return com.kevinluo.autoglm.util.DiagnosticLog.trace("DeviceIPC", "execute",
            "executable" to argv.first(), "argumentCount" to argv.size,
            "timeoutMs" to timeoutMs, "maxOutputBytes" to maxOutputBytes) {
            try {
                val result = service.executeArgs(argv.toTypedArray(), timeoutMs, maxOutputBytes).toResult()
                com.kevinluo.autoglm.util.DiagnosticLog.event("DeviceIPC", "result",
                    "executable" to argv.first(), "exitCode" to result.exitCode,
                    "timedOut" to result.timedOut, "outputTruncated" to result.outputTruncated,
                    "stdoutBytes" to result.stdout.length, "stderrBytes" to result.stderr.length)
                result
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                CommandResult(null, "", "Device service unavailable")
            }
        }
    }
}
