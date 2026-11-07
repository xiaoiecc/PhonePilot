package com.kevinluo.autoglm.input

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Acknowledgement describes InputConnection acceptance, never unobserved field contents. */
enum class InputDisposition { ACCEPTED, VERIFIED, FAILED }

enum class TextEditStage { BEFORE_EDIT, BEFORE_CLEAR, BEFORE_COMMIT }

/** Whole-field edit with a target check run on Main after IME readiness, never on IO. */
data class TextEditRequest(
    val text: String,
    val guard: (TextEditStage) -> Boolean = { true },
    val staleEditorGeneration: Long? = null,
)

data class InputResult(
    val disposition: InputDisposition,
    val message: String,
    val restorationSucceeded: Boolean = true,
) {
    val success: Boolean get() = disposition != InputDisposition.FAILED
    val verified: Boolean get() = disposition == InputDisposition.VERIFIED

    companion object {
        fun accepted(message: String) = InputResult(InputDisposition.ACCEPTED, message)
        fun success(message: String) = accepted(message)
        fun verified(message: String) = InputResult(InputDisposition.VERIFIED, message)
        fun failure(message: String) = InputResult(InputDisposition.FAILED, message)
    }
}

/** Small platform port allows the actual edit sequence to be tested without Android UI stubs. */
internal interface KeyboardEditor {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun selectAll(): Boolean
    fun isEmpty(): Boolean?
    fun commitText(text: String, cursorPosition: Int): Boolean
}

internal data class KeyboardEditorToken(val generation: Long)

/** One app-process endpoint; stale callbacks and editor leases cannot target a new field. */
internal class KeyboardGateway(private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private data class Endpoint(val owner: Any, val token: KeyboardEditorToken, val editor: KeyboardEditor)
    private val endpoint = MutableStateFlow<Endpoint?>(null)
    private var generation = 0L

    fun activate(owner: Any, editor: KeyboardEditor): KeyboardEditorToken = synchronized(this) {
        KeyboardEditorToken(++generation).also { endpoint.value = Endpoint(owner, it, editor) }
    }

    fun deactivate(owner: Any, token: KeyboardEditorToken?) = synchronized(this) {
        val active = endpoint.value
        if (active?.owner === owner && active.token == token) endpoint.value = null
    }

    fun currentToken(): KeyboardEditorToken? = endpoint.value?.token

    suspend fun awaitEditor(timeoutMs: Long = 2000, newerThan: KeyboardEditorToken? = null, excludedGeneration: Long? = null): KeyboardEditorToken? = withTimeoutOrNull(timeoutMs) {
        endpoint.first { it != null && it.token != newerThan && it.token.generation != excludedGeneration }!!.token
    }

    suspend fun input(token: KeyboardEditorToken, text: String, replace: Boolean, guard: (TextEditStage) -> Boolean = { true }): InputResult = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        if (text.length > 20000) return@withContext InputResult.failure("输入文字过长")
        val active = endpoint.value?.takeIf { it.token == token }
            ?: return@withContext InputResult.failure("输入目标已变化，请重新观察后再输入")
        fun stillCurrent() = endpoint.value?.token == token
        fun validTarget(stage: TextEditStage) = stillCurrent() && guard(stage) && stillCurrent()
        if (!validTarget(TextEditStage.BEFORE_EDIT)) {
            return@withContext InputResult.failure("输入目标或内容已变化，未发送文字，请重新观察")
        }
        val editor = active.editor
        val batched = editor.beginBatchEdit()
        try {
            if (!stillCurrent()) return@withContext InputResult.failure("输入目标已变化，已停止后续输入")
            if (replace) {
                val selected = editor.selectAll()
                if (!selected && editor.isEmpty() != true) {
                    return@withContext InputResult.failure("无法确认选中原文字，未发送替换文字")
                }
                currentCoroutineContext().ensureActive()
                if (!validTarget(TextEditStage.BEFORE_CLEAR)) return@withContext InputResult.failure("输入目标已变化，已停止后续输入")
                if (!editor.commitText("", 1)) {
                    return@withContext InputResult.failure("清空文字请求未被接受，未发送替换文字")
                }
            }
            currentCoroutineContext().ensureActive()
            if (!validTarget(TextEditStage.BEFORE_COMMIT)) return@withContext InputResult.failure("输入目标已变化，已停止后续输入")
            if (text.isNotEmpty() && !editor.commitText(text, 1)) {
                return@withContext InputResult.failure("文字输入请求未被接受")
            }
            InputResult.accepted("文字输入请求已被编辑器接受，需通过新观察确认内容")
        } finally {
            if (batched) runCatching { editor.endBatchEdit() }
        }
    }
}

internal object KeyboardInputGateway {
    val gateway: KeyboardGateway by lazy { KeyboardGateway() }
}
