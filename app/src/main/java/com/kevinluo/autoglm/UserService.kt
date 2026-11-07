package com.kevinluo.autoglm

import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.BoundedProcessRunner
import android.os.Build
import com.kevinluo.autoglm.device.CommandResultParcel

/**
 * User service for executing shell commands with elevated privileges.
 * This service runs in a separate process with Shizuku permissions.
 */
class UserService : IUserService.Stub() {
    private val commandRunner = BoundedProcessRunner(forceDestroy = { process ->
        if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly() else process.destroy()
    })
    /**
     * Destroys the service and exits the process.
     */
    override fun destroy() {
        Logger.i(TAG, "destroy")
        System.exit(0)
    }

    /**
     * Executes a shell command and returns the output.
     *
     * @param command The shell command to execute
     * @return The command output including stdout, stderr, and exit code
     */
    override fun executeArgs(argv: Array<out String>, timeoutMs: Long, maxOutputBytes: Int): CommandResultParcel = try {
        require(argv.isNotEmpty() && argv.size <= 4096 && argv.none { '\u0000' in it })
        require(argv.sumOf { it.length.toLong() } <= 200000)
        require(maxOutputBytes in 1..480000)
        val result = commandRunner.execute(argv.toList(), timeoutMs, maxOutputBytes)
        CommandResultParcel(result.exitCode, result.stdout, result.stderr, result.timedOut, result.outputTruncated)
    } catch (_: Exception) {
        // Command text, shell output and stack traces can contain user input or credentials.
        CommandResultParcel(null, "", "Command execution failed", false, false)
    }

    companion object {
        private const val TAG = "UserService"
    }
}
