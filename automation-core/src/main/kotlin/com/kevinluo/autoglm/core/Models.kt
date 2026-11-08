package com.kevinluo.autoglm.core

data class ConversationSnapshot(
    val id: String,
    val previousResponseId: String? = null,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCachedTokens: Long = 0,
    val hasInputUsage: Boolean = false,
    val hasOutputUsage: Boolean = false,
)

enum class ChatRole { USER, AGENT }

data class ChatImage(val path: String, val mimeType: String)
data class ChatInput(val text: String, val images: List<ChatImage> = emptyList())
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val images: List<ChatImage> = emptyList(),
)

data class CommandResult(
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val outputTruncated: Boolean = false,
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut && !outputTruncated

    fun requireSuccess(): CommandResult {
        check(succeeded) {
            when {
                timedOut -> "设备命令执行超时"
                outputTruncated -> "设备命令输出超过限制"
                else -> "设备命令执行失败（退出码 ${exitCode ?: "未知"}）"
            }
        }
        return this
    }
}

enum class RunPhase {
    IDLE, STARTING, RUNNING, PAUSED, WAITING_INTERACTION, CANCELLING, FINALIZING, COMPLETED, FAILED;

    val isActive: Boolean get() = this in setOf(STARTING, RUNNING, PAUSED, WAITING_INTERACTION, CANCELLING, FINALIZING)
}

enum class PauseReason { USER, DRAFT }
enum class ExecutionMode { FOREGROUND, BACKGROUND }
enum class ScreenEvent { SCREEN_OFF, USER_PRESENT }

/** A changed observation must be regenerated, never replayed against a new display. */
class ExecutionTargetChangedException : IllegalStateException("执行位置已变化；原操作未执行，请重新观察并生成操作")
enum class InteractionKind { CONFIRM, TAKEOVER, CHOICE, TEXT }

sealed interface InteractionReply {
    data class Accepted(val accepted: Boolean) : InteractionReply
    data class Choice(val index: Int?) : InteractionReply
    data class Text(val text: String?) : InteractionReply
    data object Cancelled : InteractionReply
}

data class PendingInteraction(
    val id: Long,
    val runId: String,
    val kind: InteractionKind,
    val prompt: String,
    val options: List<String> = emptyList(),
)

data class ActiveWait(
    val id: Long,
    val runId: String,
    val reason: String?,
    val startedAtMs: Long,
    val requestedMs: Int,
)

data class WaitResult(val waitId: Long, val requestedMs: Int, val actualMs: Long, val skipped: Boolean)
data class RunStep(val stepNumber: Int, val thinking: String = "", val action: String = "")

data class RunSnapshot(
    val runId: String? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val stepNumber: Int = 0,
    val thinking: String = "",
    val currentAction: String = "",
    val resultMessage: String = "",
    val conversationId: String? = null,
    val totalInputTokens: Long? = null,
    val totalOutputTokens: Long? = null,
    val pauseReasons: Set<PauseReason> = emptySet(),
    val pendingInteraction: PendingInteraction? = null,
    val activeWait: ActiveWait? = null,
    val steps: List<RunStep> = emptyList(),
    val deviceToolsRequested: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.FOREGROUND,
    val placementGeneration: Long = 0,
    val placementSwitching: Boolean = false,
    val placementError: String? = null,
)

data class RunOutcome(
    val success: Boolean,
    val message: String,
    val stepCount: Int,
    val session: ConversationSnapshot? = null,
)

data class RunCompletion(val runId: String, val outcome: RunOutcome)

data class RestoredConversation(val session: ConversationSnapshot, val messages: List<ChatMessage>)

sealed interface RunProgress {
    data class StepStarted(val number: Int) : RunProgress
    data class Thinking(val text: String) : RunProgress
    data class Action(val description: String) : RunProgress
    data class UserMessage(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val images: List<ChatImage> = emptyList(),
    ) : RunProgress
    data class AssistantMessage(val text: String, val timestamp: Long = System.currentTimeMillis()) : RunProgress
    data class Usage(val totalInputTokens: Long?, val totalOutputTokens: Long?) : RunProgress
    data class ConversationChanged(val id: String) : RunProgress
    data object DeviceToolsRequested : RunProgress
}

interface AgentRunner {
    suspend fun run(input: ChatInput, continueConversation: Boolean, control: RunControl): RunOutcome
    fun enqueueMessage(input: ChatInput): Boolean
    fun restoreConversation(session: ConversationSnapshot, messages: List<ChatMessage>): Boolean
    fun snapshot(): ConversationSnapshot
    fun canContinue(): Boolean
    fun cancelNetwork()
}

interface RunControl {
    val runId: String
    val executionMode: ExecutionMode get() = ExecutionMode.FOREGROUND
    val placementGeneration: Long get() = 0
    suspend fun <T> deviceOperation(expectedGeneration: Long? = null, block: suspend () -> T): T {
        checkpoint()
        return block()
    }
    suspend fun checkpoint()
    fun emit(progress: RunProgress)
    suspend fun interact(kind: InteractionKind, prompt: String, options: List<String> = emptyList()): InteractionReply
    suspend fun wait(durationMs: Int? = null, reason: String? = null): WaitResult
}
