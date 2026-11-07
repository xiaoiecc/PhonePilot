package com.kevinluo.autoglm.input

import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.core.CommandResult
import com.kevinluo.autoglm.device.DeviceCommandClient
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One serialized keyboard transaction: typed IME commands, local acknowledgement, restoration. */
class TextInputManager internal constructor(
    private val command: suspend (List<String>, Long) -> CommandResult,
    private val gateway: KeyboardGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readinessTimeoutMs: Long = 2000,
) {
    constructor(userService: IUserService) : this(commandRunner(userService), KeyboardInputGateway.gateway)

    private val operation = Mutex()

    suspend fun typeText(text: String): InputResult = input(text, replace = true)
    suspend fun appendText(text: String): InputResult = input(text, replace = false)
    suspend fun editText(request: TextEditRequest): InputResult = input(request.text, replace = true, request = request)

    private suspend fun input(text: String, replace: Boolean, request: TextEditRequest? = null): InputResult = operation.withLock {
        withContext(ioDispatcher) {
            if (text.length > 20000) return@withContext InputResult.failure("输入文字过长")
            var originalIme: String? = null
            var previousEditor: KeyboardEditorToken? = null
            var result: InputResult? = null
            var restored = true
            try {
                currentCoroutineContext().ensureActive()
                val current = readIme()
                if (current == null) {
                    result = InputResult.failure("无法读取当前输入法，未修改键盘或输入文字")
                } else {
                    if (!KeyboardHelper.isAutoGLMKeyboard(current)) {
                        previousEditor = gateway.currentToken()
                        val enabled = command(listOf("ime", "enable", KeyboardHelper.IME_ID), COMMAND_TIMEOUT_MS)
                        val switched = if (enabled.succeeded) {
                            originalIme = current
                            command(listOf("ime", "set", KeyboardHelper.IME_ID), COMMAND_TIMEOUT_MS).succeeded
                        } else false
                        if (!switched || !awaitSelectedIme()) {
                            result = InputResult.failure("无法切换至 AutoGLM Keyboard，请确认键盘已启用")
                        }
                    }
                    if (result == null) {
                        val editor = gateway.awaitEditor(readinessTimeoutMs, previousEditor, request?.staleEditorGeneration)
                        result = if (editor == null) InputResult.failure("键盘未连接到输入框，未发送文字")
                            else gateway.input(editor, text, replace, request?.guard ?: { true })
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                result = InputResult.failure("输入请求失败；请重新观察输入框后重试")
            } finally {
                val restoreTarget = originalIme
                if (restoreTarget != null) {
                    restored = withContext(NonCancellable + ioDispatcher) {
                        try {
                            command(listOf("ime", "set", restoreTarget), COMMAND_TIMEOUT_MS).succeeded &&
                                readIme()?.let { KeyboardHelper.sameIme(it, restoreTarget) } == true
                        }
                        catch (_: Exception) { false }
                    }
                    if (!restored) Logger.w(TAG, "Unable to restore original keyboard")
                }
            }
            val outcome = result ?: InputResult.failure("输入请求未完成")
            if (restored) outcome else outcome.copy(
                restorationSucceeded = false,
                message = outcome.message + "；原输入法恢复失败，请手动检查键盘",
            )
        }
    }

    private suspend fun readIme(timeoutMs: Long = COMMAND_TIMEOUT_MS): String? {
        val result = command(listOf("settings", "get", "secure", "default_input_method"), timeoutMs)
        if (!result.succeeded) return null
        return result.stdout.trim().takeIf(KeyboardHelper::isValidImeId)
    }

    private suspend fun awaitSelectedIme(): Boolean {
        val deadline = System.nanoTime() + readinessTimeoutMs * 1_000_000
        do {
            currentCoroutineContext().ensureActive()
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining < 100) return false
            if (readIme(minOf(COMMAND_TIMEOUT_MS, remaining))?.let(KeyboardHelper::isAutoGLMKeyboard) == true) return true
            if (System.nanoTime() >= deadline) return false
            delay(50)
        } while (true)
    }

    companion object {
        private const val TAG = "TextInputManager"
        private const val COMMAND_TIMEOUT_MS = 3000L
        private fun commandRunner(service: IUserService): suspend (List<String>, Long) -> CommandResult {
            val client = DeviceCommandClient(service)
            return { arguments, timeout -> client.executeArgs(arguments, timeoutMs = timeout) }
        }
    }
}
