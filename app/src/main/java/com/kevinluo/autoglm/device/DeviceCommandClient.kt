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
        return try {
            service.executeArgs(argv.toTypedArray(), timeoutMs, maxOutputBytes).toResult()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            CommandResult(null, "", "Device service unavailable")
        }
    }
}
