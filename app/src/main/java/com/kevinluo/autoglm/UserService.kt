package com.kevinluo.autoglm

import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.BoundedProcessRunner
import android.os.Build
import android.os.Binder
import com.kevinluo.autoglm.device.CommandResultParcel
import com.kevinluo.autoglm.device.DisplayResultParcel
import com.kevinluo.autoglm.display.PrivilegedDisplaySession

/**
 * User service for executing shell commands with elevated privileges.
 * This service runs in a separate process with Shizuku permissions.
 */
class UserService : IUserService.Stub() {
    private val commandRunner = BoundedProcessRunner(forceDestroy = { process ->
        if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly() else process.destroy()
    })
    private val displays = PrivilegedDisplaySession { argv, timeout, limit -> executeArgs(argv, timeout, limit) }

    override fun attachDisplay(runId: String): DisplayResultParcel = privileged { displays.attach(runId) }
    override fun switchDisplay(runId: String, generation: Long, background: Boolean): DisplayResultParcel =
        privileged { displays.switch(runId, generation, background) }
    override fun inspectDisplay(runId: String, generation: Long): DisplayResultParcel = privileged { displays.inspect(runId, generation) }
    override fun closeDisplay(runId: String, generation: Long): DisplayResultParcel = privileged { displays.close(runId, generation) }
    override fun captureDisplay(runId: String, generation: Long, imageId: String): DisplayResultParcel =
        privileged { displays.capture(runId, generation, imageId) }
    override fun inputDisplay(runId: String, generation: Long, operation: Int, values: IntArray): CommandResultParcel =
        privileged { displays.input(runId, generation, operation, values) }
    override fun launchOnDisplay(runId: String, generation: Long, component: String): CommandResultParcel =
        privileged { displays.launch(runId, generation, component) }
    private inline fun <T> privileged(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(identity) }
    }
    /**
     * Destroys the service and exits the process.
     */
    override fun destroy() {
        Logger.i(TAG, "destroy")
        if (!displays.shutdown()) {
            Logger.w(TAG, "Display tasks could not be handed back; preserving the service for recovery")
            return
        }
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
