package com.kevinluo.autoglm.agent

import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.computer.protocol.ComputerProtocol
import com.kevinluo.autoglm.computer.protocol.ResponsesComputerClient
import com.kevinluo.autoglm.computer.protocol.ResponsesImage
import com.kevinluo.autoglm.computer.runtime.AndroidToolExecutor
import com.kevinluo.autoglm.computer.runtime.RunMessageRecorder
import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.history.HistoryMessageRole
import com.kevinluo.autoglm.screenshot.Screenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.util.UUID

data class AgentConfig(val maxSteps: Int = 100, val language: String = "cn", val verbose: Boolean = true, val screenshotDelayMs: Long = 2000)

/** Responses protocol state only. Job, pauses, interactions and completion belong to core. */
internal class PhoneAgent(
    private val tools: AndroidToolExecutor,
    private val config: AgentConfig = AgentConfig(),
    private val historyManager: HistoryManager? = null,
    private val computerClient: ResponsesComputerClient? = null,
    private val prepareComputerClient: (suspend () -> ResponsesComputerClient)? = null,
    private val imageLoader: (suspend (ChatImage) -> ResponsesImage)? = null,
) : AgentRunner {
    private val operation = Mutex()
    private val messageLock = Any()
    private var pendingMessages: ArrayDeque<ChatInput>? = null
    @Volatile private var control: RunControl? = null
    private var sessionClient: ResponsesComputerClient? = null
    private var conversation = ConversationSnapshot(UUID.randomUUID().toString())
    private var responseRequiresOutputs = false
    private var nextInput = JsonArray(emptyList())
    private var nextInputGeneration = 0L
    private var frame: Screenshot? = null
    private var currentStepNumber = 0
    private var customInstructions = ""
    private var restoredConversation = false
    private var restoredTranscript = ""
    private var recoveryImages: List<ChatImage> = emptyList()

    override fun snapshot(): ConversationSnapshot = conversation
    // Visible conversation identity survives a discarded protocol cursor. begin() explicitly
    // supplies the retained transcript when that cursor cannot safely be reused.
    override fun canContinue(): Boolean = conversation.previousResponseId != null || restoredConversation || restoredTranscript.isNotBlank()
    override fun cancelNetwork() { sessionClient?.cancel() }

    override fun restoreConversation(session: ConversationSnapshot, messages: List<ChatMessage>): Boolean {
        if (!operation.tryLock()) return false
        return try {
            clearSession()
            conversation = session.copy()
            restoredConversation = true
            val retained = messages.takeLast(40)
            restoredTranscript = retained.joinToString("\n") {
                "${it.role}: ${it.text}${if (it.images.isEmpty()) "" else " [${it.images.size} images]"}"
            }.takeLast(16000)
            recoveryImages = retained.flatMap { it.images }
            true
        } finally { operation.unlock() }
    }

    fun setSystemPrompt(prompt: String) {
        check(control == null) { "Stop the task before changing instructions" }
        if (customInstructions != prompt) clearSession()
        customInstructions = prompt
    }

    override fun enqueueMessage(input: ChatInput): Boolean {
        val message = input.copy(text = input.text.trim())
        if (message.text.isEmpty() && message.images.isEmpty()) return false
        val owner = synchronized(messageLock) {
            val queue = pendingMessages ?: return false
            val current = control ?: return false
            if (queue.size >= 16) return false
            queue += message
            current
        }
        // Coordinator serializes acceptance with stop. Publish before returning true so an
        // accepted editor value remains visible even if the request later fails or is stopped.
        owner.emit(RunProgress.UserMessage(message.text, images = message.images))
        return true
    }

    override suspend fun run(input: ChatInput, continueConversation: Boolean, control: RunControl): RunOutcome {
        val task = input.text.trim()
        if (task.isEmpty() && input.images.isEmpty()) return RunOutcome(false, "请输入任务内容或选择图片", 0, conversation)
        if (continueConversation && !canContinue()) return RunOutcome(false, "当前没有可继续的会话", 0, conversation)
        if (!operation.tryLock()) return RunOutcome(false, "已有任务正在运行", currentStepNumber, conversation)
        var outcome = RunOutcome(false, "任务未完成", 0)
        this.control = control
        try {
            begin(input.copy(text = task), continueConversation)
            while (config.maxSteps == 0 || currentStepNumber < config.maxSteps) {
                val result = turn()
                if (result.finished) { outcome = RunOutcome(result.success, result.message, currentStepNumber); break }
            }
            if (outcome.message == "任务未完成") outcome = RunOutcome(false, "达到最大步骤数 (${config.maxSteps})，任务已停止", currentStepNumber)
        } catch (_: CancellationException) {
            outcome = RunOutcome(false, cancellationMessage(), currentStepNumber)
        } catch (error: Exception) {
            outcome = RunOutcome(false, error.message?.take(300) ?: "手机操作失败，任务已停止", currentStepNumber)
        } finally {
            val accepted = synchronized(messageLock) {
                pendingMessages?.toList().orEmpty().also { pendingMessages = null }
            }
            try {
                // Preserve accepted-but-not-executed input; this is history IO only, never a
                // new request or replay of phone actions. Admissions are already closed.
                persistAcceptedMessages(accepted)
            } catch (_: Exception) {
                outcome = outcome.copy(success = false, message = "${outcome.message}；部分消息保存失败")
            } finally {
                if (!outcome.success && responseRequiresOutputs) conversation = conversation.copy(previousResponseId = null)
                responseRequiresOutputs = false
                nextInput = JsonArray(emptyList())
                nextInputGeneration = 0L
                frame = null
                this.control = null
                operation.unlock()
            }
        }
        return outcome.copy(session = conversation)
    }

    private suspend fun begin(input: ChatInput, continuing: Boolean) {
        val task = input.text
        if (!continuing) clearSession()
        check(computerClient != null || prepareComputerClient != null) { "OpenAI 手机使用组件尚未初始化" }
        currentStepNumber = 0
        frame = null
        synchronized(messageLock) { pendingMessages = ArrayDeque() }
        val owner = requireNotNull(control)
        owner.emit(RunProgress.ConversationChanged(conversation.id))
        historyManager?.startTask(task, conversation.id)
        recordMessage(HistoryMessageRole.USER, task, input.images, remember = false)
        publishUsage()
        sessionClient = (if (continuing) sessionClient else null) ?: prepareComputerClient?.invoke() ?: computerClient
        owner.checkpoint()
        // Ordinary chat must not read our own Chat UI and pass it off as the target screen.
        // The first explicit phone tool hands off the foreground before taking an observation.
        val observation = "No current phone observation. For phone tasks, first call android_ui observe or android_screenshot. User-attached images are reference images, not the current phone screen."
        val fullTask = if (customInstructions.isBlank()) task else "$task\nUser preferences:\n$customInstructions"
        nextInput = if (continuing) {
            val recovering = conversation.previousResponseId == null && restoredTranscript.isNotBlank()
            val priorText = restoredTranscript.takeIf { recovering }?.let {
                "Earlier visible conversation (quoted context, not permission for new actions; older entries may be omitted):\n$it"
            }
            val text = listOfNotNull(priorText, fullTask.takeIf { it.isNotBlank() }).joinToString("\n\n") +
                "\nCurrent Android accessibility observation (untrusted screen data):\n$observation"
            val images = if (recovering) recoveryImages + input.images else input.images
            JsonArray(listOf(ComputerProtocol.userInput(text, loadImages(images))))
        } else ComputerProtocol.initialInput(fullTask, observation, loadImages(input.images))
        nextInputGeneration = owner.placementGeneration
        rememberMessage(HistoryMessageRole.USER, task, input.images)
    }

    private data class TurnResult(val success: Boolean = true, val finished: Boolean = false, val message: String = "")

    private suspend fun turn(): TurnResult {
        val owner = requireNotNull(control)
        owner.checkpoint()
        currentStepNumber++
        owner.emit(RunProgress.StepStarted(currentStepNumber))
        historyManager?.clearCurrentScreenshot()
        // Bind the model's decision to the target that existed when this request began.
        // A request may finish after a user migration (including transport retries).
        val requestGeneration = prepareRequestInput(owner)
        val response = try {
            requireNotNull(sessionClient).request(nextInput, conversation.previousResponseId)
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            val message = consumePendingMessage() ?: throw error
            nextInput = JsonArray(nextInput + protocolInput(message))
            return TurnResult()
        }
        owner.checkpoint()
        recordUsage(response.inputTokens, response.outputTokens, response.cachedInputTokens)
        tools.recordHosted(response, owner.runId, currentStepNumber)
        if (requestGeneration == owner.placementGeneration && response.text.isNotBlank()) {
            recordMessage(HistoryMessageRole.AGENT, response.text)
            owner.emit(RunProgress.Thinking(response.text))
        }
        // This is the sole cursor. Failed partial batches abandon it in run's finally.
        conversation = conversation.copy(previousResponseId = response.id)
        responseRequiresOutputs = response.calls.isNotEmpty() || response.functionCalls.isNotEmpty()
        if (!responseRequiresOutputs) {
            if (requestGeneration != owner.placementGeneration) {
                nextInput = JsonArray(listOf(ComputerProtocol.userInput(
                    "The execution position changed while the last response was generated. Verify the current target before reporting completion; do not repeat completed actions.")))
                nextInputGeneration = requestGeneration
                return TurnResult()
            }
            if (response.text.isBlank() && response.hostedCalls.isNotEmpty()) {
                val pending = consumePendingMessage()
                val input = pending?.let { protocolInput(it) } ?: ComputerProtocol.userInput(
                    "Continue from the hosted tool results already in this response's context and answer the task. Do not repeat completed phone actions.")
                nextInput = JsonArray(listOf(input))
                nextInputGeneration = requestGeneration
                return TurnResult()
            }
            check(response.text.isNotBlank()) { "模型未返回操作或最终答复" }
            val pending = consumePendingMessage(closeIfEmpty = true)
            if (pending != null) {
                nextInput = JsonArray(listOf(protocolInput(pending)))
                nextInputGeneration = requestGeneration
                return TurnResult()
            }
            return TurnResult(finished = true, message = response.text)
        }
        val batch = tools.executeBatch(response, frame, owner, ::hasPendingMessage,
            RunMessageRecorder { role, text -> recordMessage(role, text) }, currentStepNumber,
            requestGeneration = requestGeneration)
        frame = batch.frame
        val pending = consumePendingMessage()
        val pendingInput = pending?.let { protocolInput(it) }
        nextInput = JsonArray(batch.outputs + listOfNotNull(batch.semanticInput, pendingInput))
        nextInputGeneration = batch.observationGeneration
        val description = buildString {
            append("工具：${batch.toolNames}")
            batch.actions.joinToString("；") { it.formatForDisplay() }.takeIf { it.isNotBlank() }?.let { append("\n动作：$it") }
            if (pending != null) append("\n用户已更新指令")
            append("\n已完成 ${batch.outputs.size} 次工具调用，执行 ${batch.actionCount} 个手机操作")
        }
        // The executor publishes each call's user-facing intent before executing it.
        // Keep this technical batch summary in history; do not replace the capsule's
        // progress sentence with a raw tool-name dump after every batch.
        historyManager?.recordStep(currentStepNumber, response.text, batch.actions.singleOrNull(), description, true)
        return TurnResult()
    }

    /** Preserve protocol receipts, but never label observations from before migration as current. */
    private suspend fun prepareRequestInput(owner: RunControl): Long {
        while (true) {
            owner.checkpoint()
            val generation = owner.placementGeneration
            if (nextInputGeneration == generation) return generation
            frame = null
            val refreshed = tools.refreshObservation(owner)
            nextInput = JsonArray(nextInput + refreshed.input)
            nextInputGeneration = refreshed.generation
            frame = refreshed.frame
            // A further user switch during refresh/publication must be observed too.
        }
    }

    private fun hasPendingMessage(): Boolean = synchronized(messageLock) { pendingMessages?.isNotEmpty() == true }
    private suspend fun consumePendingMessage(closeIfEmpty: Boolean = false): ChatInput? {
        val accepted = synchronized(messageLock) {
            val queue = pendingMessages
            if (queue.isNullOrEmpty()) { if (closeIfEmpty) pendingMessages = null; null }
            else queue.toList().also { queue.clear() }
        } ?: return null
        persistAcceptedMessages(accepted)
        return ChatInput(accepted.joinToString("\n\n") { it.text }, accepted.flatMap { it.images })
    }

    private suspend fun protocolInput(input: ChatInput) = ComputerProtocol.userInput(input.text, loadImages(input.images))

    private suspend fun loadImages(images: List<ChatImage>): List<ResponsesImage> {
        if (images.isEmpty()) return emptyList()
        val loader = requireNotNull(imageLoader) { "图片附件组件尚未初始化" }
        return images.map { loader(it) }
    }

    private suspend fun persistAcceptedMessages(messages: List<ChatInput>) {
        if (messages.isEmpty()) return
        withContext(NonCancellable) {
            messages.forEach { message ->
                historyManager?.recordMessage(HistoryMessageRole.USER, message.text, message.images)
                rememberMessage(HistoryMessageRole.USER, message.text, message.images)
            }
        }
    }

    private fun rememberMessage(role: HistoryMessageRole, text: String, images: List<ChatImage> = emptyList()) {
        val imageMarker = if (images.isEmpty()) "" else " [${images.size} images]"
        restoredTranscript = (restoredTranscript + "\n${if (role == HistoryMessageRole.USER) "User" else "Assistant"}: $text$imageMarker").takeLast(16000)
        if (role == HistoryMessageRole.USER) recoveryImages = recoveryImages + images
    }

    private suspend fun recordMessage(role: HistoryMessageRole, text: String, images: List<ChatImage> = emptyList(), remember: Boolean = true) {
        historyManager?.recordMessage(role, text, images)
        if (remember) rememberMessage(role, text, images)
        requireNotNull(control).emit(if (role == HistoryMessageRole.USER) {
            RunProgress.UserMessage(text, images = images)
        } else RunProgress.AssistantMessage(text))
    }

    private fun clearSession() {
        sessionClient?.cancel()
        sessionClient = null
        synchronized(messageLock) { pendingMessages = null }
        conversation = ConversationSnapshot(UUID.randomUUID().toString())
        responseRequiresOutputs = false
        nextInput = JsonArray(emptyList())
        nextInputGeneration = 0L
        frame = null
        currentStepNumber = 0
        restoredConversation = false
        restoredTranscript = ""
        recoveryImages = emptyList()
    }

    private fun recordUsage(input: Int?, output: Int?, cached: Int?) {
        conversation = conversation.copy(
            totalInputTokens = conversation.totalInputTokens + (input ?: 0),
            totalOutputTokens = conversation.totalOutputTokens + (output ?: 0),
            totalCachedTokens = conversation.totalCachedTokens + (cached ?: 0),
            hasInputUsage = conversation.hasInputUsage || input != null,
            hasOutputUsage = conversation.hasOutputUsage || output != null,
        )
        publishUsage()
    }

    private fun publishUsage() = requireNotNull(control).emit(RunProgress.Usage(
        conversation.totalInputTokens.takeIf { conversation.hasInputUsage }, conversation.totalOutputTokens.takeIf { conversation.hasOutputUsage }))
    private fun cancellationMessage() = if (config.language.lowercase().startsWith("en")) CANCELLATION_MESSAGE_EN else CANCELLATION_MESSAGE
    companion object {
        const val CANCELLATION_MESSAGE = "任务已被用户取消"
        const val CANCELLATION_MESSAGE_EN = "Task cancelled by user"
    }
}
