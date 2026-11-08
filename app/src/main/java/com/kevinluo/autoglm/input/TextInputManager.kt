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
    private val expectedTarget: () -> Pair<Int, String>? = { null },
) {
    constructor(userService: IUserService, expectedTarget: () -> Pair<Int, String>? = { null }) :
        this(commandRunner(userService), KeyboardInputGateway.gateway, expectedTarget = expectedTarget)

    private val operation = Mutex()

    suspend fun typeText(text: String): InputResult = input(text, replace = true)
    suspend fun appendText(text: String): InputResult = input(text, replace = false)
    suspend fun editText(request: TextEditRequest): InputResult = input(request.text, replace = true, request = request)

    private suspend fun input(text: String, replace: Boolean, request: TextEditRequest? = null): InputResult = operation.withLock {
        com.kevinluo.autoglm.util.DiagnosticLog.event("Input", "transaction.started",
            "textLength" to text.length, "replace" to replace,
            "hasEditRequest" to (request != null), "readinessTimeoutMs" to readinessTimeoutMs,
            "expectedTarget" to expectedTarget())
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
                        when {
                            !enabled.succeeded -> result = InputResult.failure("AutoGLM Keyboard 启用命令未成功（exitCode=${enabled.exitCode ?: "未知"}）")
                            !switched -> result = InputResult.failure("AutoGLM Keyboard 切换命令未成功，请确认键盘已启用")
                            !awaitSelectedIme() -> result = InputResult.failure("切换命令已接受，但未读取到 AutoGLM Keyboard 成为当前输入法")
                        }
                    }
                    if (result == null) {
                        val editor = gateway.awaitEditor(readinessTimeoutMs, previousEditor, request?.staleEditorGeneration)
                        val target = expectedTarget()
                        val focusedDisplay = if (target != null && editor?.packageName == target.second && target.first > 0) {
                            command(listOf("dumpsys", "input_method"), COMMAND_TIMEOUT_MS).let {
                                if (it.succeeded) ImeTargetIdentity.focusedDisplay(it.stdout) else null
                            }
                        } else null
                        val targetVerified = target == null || (editor?.packageName == target.second && target.first > 0 &&
                            focusedDisplay == target.first)
                        com.kevinluo.autoglm.util.DiagnosticLog.event("Input", "target.checked",
                            "expectedDisplay" to target?.first, "expectedPackage" to target?.second,
                            "editorGeneration" to editor?.generation, "editorPackage" to editor?.packageName,
                            "focusedDisplay" to focusedDisplay, "verified" to targetVerified)
                        result = if (editor == null) InputResult.failure("键盘未连接到输入框，未发送文字")
                            else if (!targetVerified) InputResult.failure("无法确认键盘连接到后台目标应用，未发送文字；可切到前台继续")
                            else gateway.input(editor, text, replace) { stage ->
                                expectedTarget() == target && (request?.guard?.invoke(stage) ?: true)
                            }
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
            com.kevinluo.autoglm.util.DiagnosticLog.event("Input", "transaction.completed",
                "disposition" to outcome.disposition, "messageLength" to outcome.message.length,
                "restored" to restored, "originalIme" to originalIme,
                "previousEditorGeneration" to previousEditor?.generation,
                "previousEditorPackage" to previousEditor?.packageName)
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
