package com.kevinluo.autoglm.core

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** Owns one execution and every mutable user-visible task fact. Android views only submit commands. */
class RunCoordinator(
    private val scope: CoroutineScope,
    private val runnerProvider: () -> AgentRunner?,
    private val onFinalize: suspend (runId: String, outcome: RunOutcome) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val ids = AtomicLong()
    private val mutableSnapshot = MutableStateFlow(RunSnapshot())
    val snapshot: StateFlow<RunSnapshot> = mutableSnapshot.asStateFlow()
    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = mutableMessages.asStateFlow()
    private val mutableCompletions = MutableSharedFlow<RunCompletion>(extraBufferCapacity = 64)
    val completions: SharedFlow<RunCompletion> = mutableCompletions.asSharedFlow()

    private class InteractionSlot(val pending: PendingInteraction, val reply: CompletableDeferred<InteractionReply>)
    private class WaitSlot(val wait: ActiveWait, var skipped: Boolean = false)
    private class ActiveRun(val id: String, val runner: AgentRunner) {
        lateinit var job: Job
        var outcome: RunOutcome? = null
        var stage = RunPhase.STARTING
        val pauses = mutableSetOf<PauseReason>()
        var interaction: InteractionSlot? = null
        var wait: WaitSlot? = null
    }

    private var active: ActiveRun? = null
    private var selected: RestoredConversation? = null
    private var loadGeneration = 0L

    val isBusy: Boolean get() = synchronized(lock) { active != null }
    val canContinue: Boolean get() = synchronized(lock) {
        active == null && (selected != null ||
            (mutableSnapshot.value.conversationId != null && runnerProvider()?.canContinue() == true))
    }

    /** Acceptance is synchronous, so editors clear only input that was actually accepted. */
    fun submit(text: String, forceNew: Boolean = false): Boolean {
        if (!scope.isActive) return false
        val task = text.trim().takeIf { it.isNotEmpty() } ?: return false
        val owner: ActiveRun
        val continuing: Boolean
        synchronized(lock) {
            val running = active
            if (running != null) {
                if (forceNew || !running.stage.acceptsCommands || !running.job.isActive) return false
                val interaction = running.interaction
                if (interaction?.pending?.kind == InteractionKind.TEXT) {
                    running.pauses.remove(PauseReason.DRAFT)
                    return completeReplyLocked(running, interaction, InteractionReply.Text(task))
                }
                val accepted = running.runner.enqueueMessage(task)
                if (accepted) {
                    running.pauses.remove(PauseReason.DRAFT)
                    refreshExecutionStageLocked(running)
                }
                return accepted
            }
            val runner = runnerProvider() ?: return false
            val pending = if (forceNew) null else selected
            if (pending != null && !runner.restoreConversation(pending.session, pending.messages)) return false
            continuing = !forceNew && (pending != null ||
                (mutableSnapshot.value.conversationId != null && runner.canContinue()))
            loadGeneration++
            selected = null
            owner = ActiveRun(UUID.randomUUID().toString(), runner)
            active = owner
            mutableSnapshot.value = RunSnapshot(
                runId = owner.id,
                phase = owner.stage,
                conversationId = if (continuing) runner.snapshot().id else null,
                totalInputTokens = if (continuing) runner.snapshot().let { it.totalInputTokens.takeIf { _ -> it.hasInputUsage } } else null,
                totalOutputTokens = if (continuing) runner.snapshot().let { it.totalOutputTokens.takeIf { _ -> it.hasOutputUsage } } else null,
            )
            if (!continuing) mutableMessages.value = emptyList()
            owner.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    synchronized(lock) { if (active === owner) refreshExecutionStageLocked(owner) }
                    owner.outcome = runner.run(task, continuing, Control(owner))
                } catch (_: CancellationException) {
                    owner.outcome = RunOutcome(false, "任务已被用户取消", mutableSnapshot.value.stepNumber, runner.snapshot())
                } catch (_: Exception) {
                    owner.outcome = RunOutcome(false, "任务执行失败，请检查服务后重试", mutableSnapshot.value.stepNumber, runner.snapshot())
                }
            }
            owner.job.invokeOnCompletion { finalize(owner) }
        }
        owner.job.start()
        return true
    }

    private fun finalize(owner: ActiveRun) {
        val outcome = synchronized(lock) {
            if (active !== owner || owner.stage == RunPhase.FINALIZING || !owner.stage.isActive) return
            val priorStage = owner.stage
            owner.stage = RunPhase.FINALIZING
            owner.interaction?.reply?.complete(InteractionReply.Cancelled)
            owner.interaction = null
            owner.wait = null
            // A cancelled lazy Job may never have entered run(false), so the runner can
            // still contain another conversation. Never invent a session for an unentered run.
            owner.outcome = if (priorStage == RunPhase.CANCELLING) {
                RunOutcome(false, "任务已被用户取消", mutableSnapshot.value.stepNumber, owner.outcome?.session)
            } else owner.outcome ?: RunOutcome(false, "任务已被用户取消", mutableSnapshot.value.stepNumber)
            projectOwnerLocked(owner)
            requireNotNull(owner.outcome)
        }
        // The run Job is already complete here. This finalizer deliberately retains ownership
        // through persistence, including when cancellation happened before a lazy run started.
        scope.launch(NonCancellable) {
            val persisted = try {
                onFinalize(owner.id, outcome)
                outcome
            } catch (_: Exception) {
                outcome.copy(success = false, message = "${outcome.message}；任务记录保存失败")
            }
            val completed = synchronized(lock) {
                if (active !== owner) return@synchronized false
                owner.outcome = persisted
                owner.stage = if (persisted.success) RunPhase.COMPLETED else RunPhase.FAILED
                owner.pauses.clear()
                active = null
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    phase = owner.stage,
                    resultMessage = persisted.message,
                    conversationId = persisted.session?.id ?: mutableSnapshot.value.conversationId,
                    totalInputTokens = persisted.session?.takeIf { it.hasInputUsage }?.totalInputTokens ?: mutableSnapshot.value.totalInputTokens,
                    totalOutputTokens = persisted.session?.takeIf { it.hasOutputUsage }?.totalOutputTokens ?: mutableSnapshot.value.totalOutputTokens,
                    pauseReasons = emptySet(), pendingInteraction = null, activeWait = null,
                )
                true
            }
            if (completed) mutableCompletions.emit(RunCompletion(owner.id, persisted))
        }
    }

    fun pauseUser(): Boolean = addPause(PauseReason.USER)

    fun pauseForDraft(): Boolean = synchronized(lock) {
        if (active?.interaction != null) false else addPause(PauseReason.DRAFT)
    }

    private fun addPause(reason: PauseReason): Boolean = synchronized(lock) {
        val owner = active ?: return false
        if (!owner.stage.acceptsCommands) return false
        val changed = owner.pauses.add(reason)
        refreshExecutionStageLocked(owner)
        changed
    }

    fun resumeUser(): Boolean = removePause(PauseReason.USER)
    fun onEditorBlur(hasDraft: Boolean) { if (!hasDraft) removePause(PauseReason.DRAFT) }

    private fun removePause(reason: PauseReason): Boolean = synchronized(lock) {
        val owner = active ?: return false
        if (!owner.stage.acceptsCommands) return false
        val changed = owner.pauses.remove(reason)
        refreshExecutionStageLocked(owner)
        changed
    }

    fun stop(): Boolean {
        val owner = synchronized(lock) {
            val current = active ?: return false
            if (!current.stage.acceptsCommands) return false
            current.stage = RunPhase.CANCELLING
            projectOwnerLocked(current)
            current
        }
        try { owner.runner.cancelNetwork() } catch (_: Exception) { /* Cancellation still owns the Job. */ }
        finally { owner.job.cancel() }
        return true
    }

    fun reset(): Boolean = synchronized(lock) {
        if (active != null) return false
        loadGeneration++
        selected = null
        mutableSnapshot.value = RunSnapshot()
        mutableMessages.value = emptyList()
        true
    }

    suspend fun loadConversation(loader: suspend () -> RestoredConversation?): Boolean {
        val generation = synchronized(lock) {
            if (active != null) return false
            ++loadGeneration
        }
        val restored = try { loader() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            ?: return false
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            if (active != null || generation != loadGeneration) return false
            val runner = runnerProvider()
            if (runner != null && !runner.restoreConversation(restored.session, restored.messages)) return false
            selected = restored.copy(messages = restored.messages.toList())
            mutableSnapshot.value = RunSnapshot(
                phase = RunPhase.COMPLETED, conversationId = restored.session.id,
                totalInputTokens = restored.session.totalInputTokens.takeIf { restored.session.hasInputUsage },
                totalOutputTokens = restored.session.totalOutputTokens.takeIf { restored.session.hasOutputUsage },
            )
            mutableMessages.value = restored.messages.toList()
            true
        }
    }

    fun reply(runId: String, interactionId: Long, reply: InteractionReply): Boolean = synchronized(lock) {
        val owner = active?.takeIf { it.id == runId && it.stage.acceptsCommands && it.job.isActive } ?: return false
        val slot = owner.interaction?.takeIf { it.pending.id == interactionId } ?: return false
        completeReplyLocked(owner, slot, reply)
    }

    private fun completeReplyLocked(owner: ActiveRun, slot: InteractionSlot, reply: InteractionReply): Boolean {
        val valid = when (reply) {
            InteractionReply.Cancelled -> true
            is InteractionReply.Accepted -> slot.pending.kind in setOf(InteractionKind.CONFIRM, InteractionKind.TAKEOVER)
            is InteractionReply.Choice -> slot.pending.kind == InteractionKind.CHOICE &&
                (reply.index == null || reply.index in slot.pending.options.indices)
            is InteractionReply.Text -> slot.pending.kind == InteractionKind.TEXT && (reply.text == null || reply.text.isNotBlank())
        }
        if (!valid || slot.reply.isCompleted) return false
        owner.interaction = null
        refreshExecutionStageLocked(owner)
        return slot.reply.complete(reply)
    }

    fun skipWait(runId: String, waitId: Long): Boolean = synchronized(lock) {
        val owner = active?.takeIf { it.id == runId && it.stage.acceptsCommands && it.job.isActive } ?: return false
        val slot = owner.wait?.takeIf { it.wait.id == waitId && !it.skipped } ?: return false
        slot.skipped = true
        true
    }

    private val RunPhase.acceptsCommands: Boolean
        get() = this in setOf(RunPhase.STARTING, RunPhase.RUNNING, RunPhase.PAUSED, RunPhase.WAITING_INTERACTION)

    private val RunPhase.canExecute: Boolean
        get() = this == RunPhase.STARTING || this == RunPhase.RUNNING

    /** Pauses and an interaction are payloads of the execution stage, never separate stop/finalizer flags. */
    private fun refreshExecutionStageLocked(owner: ActiveRun) {
        if (active !== owner) return
        if (owner.stage.acceptsCommands) {
            owner.stage = when {
                owner.interaction != null -> RunPhase.WAITING_INTERACTION
                owner.pauses.isNotEmpty() -> RunPhase.PAUSED
                else -> RunPhase.RUNNING
            }
        }
        projectOwnerLocked(owner)
    }

    /** The immutable public snapshot projects the sole lifecycle stage; it never drives admission. */
    private fun projectOwnerLocked(owner: ActiveRun) {
        if (active !== owner) return
        mutableSnapshot.value = mutableSnapshot.value.copy(
            phase = owner.stage,
            pauseReasons = owner.pauses.toSet(),
            pendingInteraction = owner.interaction?.pending,
            activeWait = owner.wait?.wait,
        )
    }

    private inner class Control(private val owner: ActiveRun) : RunControl {
        override val runId: String get() = owner.id

        override suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            mutableSnapshot.first {
                synchronized(lock) {
                    active !== owner || owner.stage !in setOf(RunPhase.PAUSED, RunPhase.WAITING_INTERACTION)
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (active !== owner || !owner.stage.canExecute) throw CancellationException("Expired run")
            }
        }

        override fun emit(progress: RunProgress) = synchronized(lock) {
            if (active !== owner || !owner.stage.acceptsCommands) return
            val state = mutableSnapshot.value
            when (progress) {
                is RunProgress.StepStarted -> mutableSnapshot.value = state.copy(stepNumber = progress.number,
                    thinking = "", currentAction = "", steps = state.steps + RunStep(progress.number))
                is RunProgress.Thinking -> mutableSnapshot.value = state.copy(thinking = progress.text,
                    steps = state.steps.mapIndexed { index, step -> if (index == state.steps.lastIndex) step.copy(thinking = progress.text) else step })
                is RunProgress.Action -> mutableSnapshot.value = state.copy(currentAction = progress.description,
                    steps = state.steps.mapIndexed { index, step -> if (index == state.steps.lastIndex) step.copy(action = progress.description) else step })
                is RunProgress.UserMessage -> mutableMessages.value = mutableMessages.value + ChatMessage(ChatRole.USER, progress.text, progress.timestamp)
                is RunProgress.AssistantMessage -> mutableMessages.value = mutableMessages.value + ChatMessage(ChatRole.AGENT, progress.text, progress.timestamp)
                is RunProgress.Usage -> mutableSnapshot.value = state.copy(totalInputTokens = progress.totalInputTokens, totalOutputTokens = progress.totalOutputTokens)
                is RunProgress.ConversationChanged -> mutableSnapshot.value = state.copy(conversationId = progress.id)
            }
        }

        override suspend fun interact(kind: InteractionKind, prompt: String, options: List<String>): InteractionReply {
            require(prompt.isNotBlank())
            require(kind != InteractionKind.CHOICE || options.isNotEmpty())
            checkpoint()
            val slot = synchronized(lock) {
                if (active !== owner || !owner.stage.canExecute) throw CancellationException("Expired run")
                check(owner.interaction == null) { "Another interaction is pending" }
                InteractionSlot(PendingInteraction(ids.incrementAndGet(), owner.id, kind, prompt, options.toList()), CompletableDeferred()).also {
                    owner.interaction = it
                    refreshExecutionStageLocked(owner)
                }
            }
            return try { slot.reply.await() } finally {
                synchronized(lock) {
                    if (active === owner && owner.interaction === slot) {
                        owner.interaction = null
                        refreshExecutionStageLocked(owner)
                    }
                }
            }
        }

        override suspend fun wait(durationMs: Int?, reason: String?): WaitResult {
            val duration = durationMs ?: 2000
            require(duration in 0..60000)
            checkpoint()
            val slot = synchronized(lock) {
                if (active !== owner || !owner.stage.canExecute) throw CancellationException("Expired run")
                check(owner.wait == null) { "Another wait is pending" }
                WaitSlot(ActiveWait(ids.incrementAndGet(), owner.id, reason, clockMs(), duration)).also {
                    owner.wait = it
                    refreshExecutionStageLocked(owner)
                }
            }
            return try {
                while (clockMs() - slot.wait.startedAtMs < duration) {
                    checkpoint()
                    if (synchronized(lock) { slot.skipped }) break
                    delay(minOf(50L, (duration - (clockMs() - slot.wait.startedAtMs)).coerceAtLeast(1)))
                }
                WaitResult(slot.wait.id, duration, (clockMs() - slot.wait.startedAtMs).coerceAtLeast(0), slot.skipped)
            } finally {
                synchronized(lock) {
                    if (active === owner && owner.wait === slot) {
                        owner.wait = null
                        refreshExecutionStageLocked(owner)
                    }
                }
            }
        }
    }
}
