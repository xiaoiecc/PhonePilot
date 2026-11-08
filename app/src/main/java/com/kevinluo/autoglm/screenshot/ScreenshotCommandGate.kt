package com.kevinluo.autoglm.screenshot

import java.io.IOException
import com.kevinluo.autoglm.core.CommandResult
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal class ScreenshotCaptureException(message: String) : IOException(message)

/** A partial or timed-out image must never be mistaken for a successful capture. */
internal fun requireSuccessfulScreenshotCommand(result: CommandResult) {
    if (!result.succeeded) {
        throw ScreenshotCaptureException("截图命令执行失败或超时，任务已停止；请检查 Shizuku 后重试")
    }
}

/**
 * A synchronous Binder call cannot be interrupted by a coroutine deadline. Isolate at most
 * one call, stop awaiting it on deadline, and reject further calls until it really returns.
 * Only screenshot/read/cleanup operations belong here; never enqueue phone input actions.
 */
internal class ScreenshotCommandGate(
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenshot-binder").apply { isDaemon = true }
    },
) {
    private val occupied = AtomicBoolean(false)
    internal val isBusy: Boolean get() = occupied.get()

    suspend fun <T> execute(timeoutMs: Long, command: () -> T): T = com.kevinluo.autoglm.util.DiagnosticLog.traceSuspend(
        "ScreenshotGate", "execute", "timeoutMs" to timeoutMs, "busyBefore" to isBusy) {
        require(timeoutMs > 0)
        currentCoroutineContext().ensureActive()
        if (!occupied.compareAndSet(false, true)) throw ScreenshotCaptureException("上一次截图请求尚未结束，请检查 Shizuku 后重试")
        var submitted = false
        try {
            return@traceSuspend withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        submitted = true
                        executor.execute {
                            val result = runCatching(command)
                            occupied.set(false)
                            com.kevinluo.autoglm.util.DiagnosticLog.event("ScreenshotGate", "binder.returned",
                                "success" to (result.isSuccess), "busyAfter" to isBusy)
                            if (continuation.isActive) continuation.resumeWith(result)
                        }
                    } catch (error: Exception) {
                        occupied.set(false)
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            com.kevinluo.autoglm.util.DiagnosticLog.event("ScreenshotGate", "deadline",
                "timeoutMs" to timeoutMs, "callerActive" to currentCoroutineContext().isActive,
                "binderStillBusy" to isBusy)
            if (!currentCoroutineContext().isActive) throw error
            throw ScreenshotCaptureException("截图命令等待超时，任务已停止；请检查 Shizuku 后重试")
        } finally {
            // Cancellation before entering the continuation must not leave a phantom owner.
            if (!submitted) occupied.set(false)
        }
    }
}
