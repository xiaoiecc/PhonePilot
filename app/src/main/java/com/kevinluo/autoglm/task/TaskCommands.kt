package com.kevinluo.autoglm.task

import android.content.Context
import com.kevinluo.autoglm.ComponentManager
import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.history.HistoryMessage
import com.kevinluo.autoglm.history.HistoryMessageRole
import com.kevinluo.autoglm.ui.AgentNotificationHelper
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Android command facade. The pure coordinator is the only task-state writer. */
object TaskExecutionManager {
    private var applicationContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val coordinator = RunCoordinator(scope, { components()?.phoneAgent }, onFinalize = { _, outcome ->
        val releaseError = runCatching { components()?.releaseExecutionPlacement() }.exceptionOrNull()
        val history = components()?.historyManager
        outcome.session?.let { history?.updateConversationSession(it) }
        history?.completeTask(outcome.success && releaseError == null,
            outcome.message + if (releaseError != null) "；应用归还未完成" else "")
        applicationContext?.let(AgentNotificationHelper::cancel)
        if (releaseError != null) throw releaseError
    }, switchPlacement = { runId, mode ->
        checkNotNull(components()).switchExecutionPlacement(runId, mode)
    })
    val taskState: StateFlow<TaskExecutionState> = ProjectedStateFlow(coordinator.snapshot, ::TaskExecutionState)
    val messages: StateFlow<List<HistoryMessage>> = ProjectedStateFlow(coordinator.messages) { messages ->
        messages.map {
            HistoryMessage(if (it.role == ChatRole.USER) HistoryMessageRole.USER else HistoryMessageRole.AGENT,
                it.text, it.timestamp, it.images)
        }
    }
    val steps: StateFlow<List<TaskStep>> = ProjectedStateFlow(coordinator.snapshot) { state ->
        state.steps.map { TaskStep(it.stepNumber, it.thinking, it.action) }
    }
    val activeWait: StateFlow<ActiveWait?> = ProjectedStateFlow(coordinator.snapshot) { it.activeWait }
    val pendingInteraction: StateFlow<PendingInteraction?> = ProjectedStateFlow(coordinator.snapshot) { it.pendingInteraction }
    val completions get() = coordinator.completions
    val deviceToolRequests get() = coordinator.deviceToolRequests
    val executionMode: StateFlow<ExecutionMode> = ProjectedStateFlow(coordinator.snapshot) { it.executionMode }
    val placementSwitching: StateFlow<Boolean> = ProjectedStateFlow(coordinator.snapshot) { it.placementSwitching }
    val placementError: StateFlow<String?> = ProjectedStateFlow(coordinator.snapshot) { it.placementError }

    fun requestExecutionMode(mode: ExecutionMode): Boolean = coordinator.snapshot.value.runId?.let {
        coordinator.requestExecutionMode(it, mode)
    } ?: false

    fun sendAndResume(text: String): Boolean {
        val pending = pendingInteraction.value
        if (pending != null) return if (pending.kind == InteractionKind.TEXT) {
            coordinator.reply(pending.runId, pending.id, InteractionReply.Text(text.trim()))
        } else false
        return (coordinator.isBusy || canStartTask()) && coordinator.submit(text, resumePaused = true)
    }

    fun initialize(context: Context) {
        if (applicationContext != null) return
        applicationContext = context.applicationContext
        FloatingWindowStateManager.initialize(context)
        scope.launch {
            coordinator.snapshot.collect { state ->
                FloatingWindowStateManager.onRunChanged(state)
                if (state.pendingInteraction?.kind == InteractionKind.TEXT) AgentNotificationHelper.showWaitingForUser(context)
                else AgentNotificationHelper.cancel(context)
            }
        }
    }

    private fun components() = applicationContext?.let(ComponentManager::getInstance)
    enum class StartTaskBlockReason { NONE, SERVICE_NOT_CONNECTED, PHONE_AGENT_NULL, TASK_ALREADY_RUNNING }

    fun getStartTaskBlockReason(): StartTaskBlockReason = when {
        coordinator.isBusy -> StartTaskBlockReason.TASK_ALREADY_RUNNING
        components()?.isServiceConnected != true -> StartTaskBlockReason.SERVICE_NOT_CONNECTED
        components()?.phoneAgent == null -> StartTaskBlockReason.PHONE_AGENT_NULL
        else -> StartTaskBlockReason.NONE
    }
    fun canStartTask(): Boolean = getStartTaskBlockReason() == StartTaskBlockReason.NONE
    fun isTaskRunning(): Boolean = coordinator.isBusy
    fun canContinueConversation(): Boolean = coordinator.canContinue
    fun startTask(description: String, images: List<ChatImage> = emptyList()): Boolean =
        canStartTask() && coordinator.submit(description, forceNew = true, images = images)
    fun startNewConversation(description: String, images: List<ChatImage> = emptyList()): Boolean = startTask(description, images)
    fun continueConversation(message: String, images: List<ChatImage> = emptyList()): Boolean =
        canStartTask() && coordinator.canContinue && coordinator.submit(message, images = images)

    fun sendMessage(text: String, images: List<ChatImage> = emptyList()): Boolean {
        if (text.isBlank() && images.isEmpty()) return false
        val pending = pendingInteraction.value
        if (pending?.kind == InteractionKind.TEXT) {
            if (images.isNotEmpty()) return false
            return coordinator.reply(pending.runId, pending.id, InteractionReply.Text(text.trim()))
        }
        return (coordinator.isBusy || canStartTask()) && coordinator.submit(text, images = images)
    }
    fun pauseTask() = coordinator.pauseUser()
    fun resumeTask() = coordinator.resumeUser()
    fun pauseForDraft() { coordinator.pauseForDraft() }
    fun onEditorBlur(hasDraft: Boolean) = coordinator.onEditorBlur(hasDraft)
    fun cancelTask() { coordinator.stop() }
    fun resetTask() { coordinator.reset() }
    fun onRunnerUnavailable() { coordinator.stop() }
    fun replyInteraction(runId: String, interactionId: Long, reply: InteractionReply) = coordinator.reply(runId, interactionId, reply)
    fun cancelPendingInteraction(): Boolean {
        val pending = pendingInteraction.value ?: return false
        return replyInteraction(pending.runId, pending.id, InteractionReply.Cancelled)
    }
    fun finishWaiting(): Boolean {
        val wait = activeWait.value ?: return false
        return coordinator.skipWait(wait.runId, wait.id)
    }
    suspend fun loadConversation(id: String): Boolean = coordinator.loadConversation {
        components()?.historyManager?.loadConversation(id)?.let { restored ->
            RestoredConversation(restored.session, restored.messages.map {
                ChatMessage(if (it.role == HistoryMessageRole.USER) ChatRole.USER else ChatRole.AGENT,
                    it.text, it.timestamp, it.images)
            })
        }
    }
}
