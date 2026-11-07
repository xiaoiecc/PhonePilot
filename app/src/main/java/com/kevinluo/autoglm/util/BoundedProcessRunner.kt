package com.kevinluo.autoglm.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A remote shell command must never hold its Binder caller indefinitely. */
internal class BoundedProcessRunner(
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
    // Safe baseline for JVM callers and Android 24/25. UserService supplies the
    // API-gated force-destroy implementation on Android versions that support it.
    private val forceDestroy: (Process) -> Unit = { it.destroy() },
) {
    data class Result(
        val stdout: String, val stderr: String, val exitCode: Int,
        val timedOut: Boolean = false, val outputTruncated: Boolean = false,
    ) {
        fun formatted(): String = buildString {
            if (stdout.isNotEmpty()) append(stdout.trimEnd('\r', '\n')).append('\n')
            if (stderr.isNotEmpty()) append("\n[stderr]\n").append(stderr.trimEnd('\r', '\n')).append('\n')
            append("\n[exit code: ").append(exitCode).append(']')
        }
    }

    fun execute(command: List<String>, timeoutMs: Long, maxOutputBytes: Int = 500_000): Result {
        require(command.isNotEmpty())
        require(timeoutMs in 100..120_000)
        require(maxOutputBytes in 1..500_000)
        val process = startProcess(command)
        val stopped = AtomicBoolean(false)
        val outputLimit = AtomicBoolean(false)
        val remaining = AtomicInteger(maxOutputBytes)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val readers = listOf(
            drain(process.inputStream, stdout, stopped, outputLimit, remaining),
            drain(process.errorStream, stderr, stopped, outputLimit, remaining),
        )
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var exitCode: Int? = null
        var timedOut = false
        try {
            while (true) {
                exitCode = exitCode(process)
                if (exitCode != null || outputLimit.get()) break
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                Thread.sleep(5)
            }
        } finally {
            // Once the command exits, drain only bytes already available. An escaped child
            // retaining the pipe cannot keep these readers, their closes, or Binder alive.
            stopped.set(true)
            if (exitCode == null) {
                runCatching { process.destroy() }
                val grace = System.nanoTime() + 100_000_000
                while (exitCode(process) == null && System.nanoTime() < grace) Thread.sleep(5)
                if (exitCode(process) == null) runCatching { forceDestroy(process) }
            }
            readers.forEach { it.join(250) }
            // Readers never wait for new pipe data and own no lock while sleeping.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
        val diagnostic = when {
            timedOut -> "Error: Command timed out"
            outputLimit.get() -> "Error: Command output limit exceeded"
            else -> null
        }
        return Result(
            stdout.toString(Charsets.UTF_8.name()),
            listOfNotNull(stderr.toString(Charsets.UTF_8.name()).takeIf { it.isNotEmpty() }, diagnostic).joinToString("\n"),
            if (timedOut) 124 else if (outputLimit.get()) 125 else exitCode ?: 125,
            timedOut = timedOut,
            outputTruncated = outputLimit.get(),
        )
    }

    private fun drain(
        input: InputStream,
        output: ByteArrayOutputStream,
        stopped: AtomicBoolean,
        outputLimit: AtomicBoolean,
        remaining: AtomicInteger,
    ): Thread = Thread({
        val buffer = ByteArray(4096)
        try {
            while (!outputLimit.get()) {
                val available = input.available()
                if (available > 0) {
                    val read = input.read(buffer, 0, minOf(available, buffer.size))
                    if (read < 0) break
                    val allowed = remaining.getAndUpdate { (it - read).coerceAtLeast(0) }.coerceAtMost(read)
                    if (allowed > 0) output.write(buffer, 0, allowed)
                    if (allowed < read) outputLimit.set(true)
                } else if (stopped.get()) break else Thread.sleep(5)
            }
        } catch (_: Exception) {
            // Destroying the command closes its pipes; expected during timeout/limit cleanup.
        }
    }, "bounded-command-reader").apply { isDaemon = true; start() }

    private fun exitCode(process: Process): Int? = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
}
