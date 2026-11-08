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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one execution and every mutable user-visible task fact. Android views only submit commands. */
class RunCoordinator(
    private val scope: CoroutineScope,
    private val runnerProvider: () -> AgentRunner?,
    private val onFinalize: suspend (runId: String, outcome: RunOutcome) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val switchPlacement: suspend (String, ExecutionMode) -> Unit = { _, _ -> error("当前设备尚未支持切换执行位置") },
) {
    private val lock = Any()
    private val deviceGate = Mutex()
    private val ids = AtomicLong()
    private val mutableSnapshot = MutableStateFlow(RunSnapshot())
    val snapshot: StateFlow<RunSnapshot> = mutableSnapshot.asStateFlow()
    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = mutableMessages.asStateFlow()
    private val mutableCompletions = MutableSharedFlow<RunCompletion>(extraBufferCapacity = 64)
    val completions: SharedFlow<RunCompletion> = mutableCompletions.asSharedFlow()
    private val mutableDeviceToolRequests = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val deviceToolRequests: SharedFlow<String> = mutableDeviceToolRequests.asSharedFlow()

    private class InteractionSlot(val pending: PendingInteraction, val reply: CompletableDeferred<InteractionReply>)
    private class WaitSlot(val wait: ActiveWait, var skipped: Boolean = false)
    private class ActiveRun(val id: String, val runner: AgentRunner) {
        lateinit var job: Job
        var outcome: RunOutcome? = null
        var stage = RunPhase.STARTING
        val pauses = mutableSetOf<PauseReason>()
        var interaction: InteractionSlot? = null
        var wait: WaitSlot? = null
        var placementJob: Job? = null
        var mode = ExecutionMode.FOREGROUND
        var placementGeneration = 0L
        var placementRequest: ExecutionMode? = null
        var placementError: String? = null
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
    fun submit(text: String, forceNew: Boolean = false, images: List<ChatImage> = emptyList(), resumePaused: Boolean = false): Boolean {
        if (!scope.isActive) return false
        val task = text.trim()
        val attachments = images.toList()
        if ((task.isEmpty() && attachments.isEmpty()) || attachments.any {
            it.path.isBlank() || it.mimeType !in setOf("image/webp", "image/png", "image/jpeg")
        }) return false
        val input = ChatInput(task, attachments)
        val owner: ActiveRun
        val continuing: Boolean
        synchronized(lock) {
            val running = active
            if (running != null) {
                if (forceNew || !running.stage.acceptsCommands || !running.job.isActive) return false
                val interaction = running.interaction
                if (resumePaused && (interaction != null || running.placementError != null)) return false
                if (interaction?.pending?.kind == InteractionKind.TEXT) {
                    if (attachments.isNotEmpty()) return false
                    running.pauses.remove(PauseReason.DRAFT)
                    return completeReplyLocked(running, interaction, InteractionReply.Text(task))
                }
                val accepted = running.runner.enqueueMessage(input)
                if (accepted) {
                    running.pauses.remove(PauseReason.DRAFT)
                    if (resumePaused) running.pauses.remove(PauseReason.USER)
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
                    owner.outcome = runner.run(input, continuing, Control(owner))
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
            owner.placementJob?.cancelAndJoin()
            val persisted = try {
                onFinalize(owner.id, outcome)
                outcome
            } catch (_: Exception) {
                outcome.copy(success = false, message = "${outcome.message}；任务收尾或记录保存失败，请检查应用归还与任务记录")
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
                    placementSwitching = false,
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
        if (!owner.stage.acceptsCommands || owner.placementError != null) return false
        val changed = owner.pauses.remove(reason)
        refreshExecutionStageLocked(owner)
        changed
    }

    /** The same device gate serializes placement with accepted device operations. */
    fun requestExecutionMode(runId: String, mode: ExecutionMode): Boolean {
        val job = synchronized(lock) {
            val owner = active?.takeIf { it.id == runId && it.stage.acceptsCommands && it.job.isActive } ?: return false
            if (owner.placementRequest != null || owner.mode == mode && owner.placementError == null) return false
            owner.placementRequest = mode
            owner.placementError = null
            projectOwnerLocked(owner)
            mutableDeviceToolRequests.tryEmit(owner.id)
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    deviceGate.withLock {
                        synchronized(lock) {
                            if (active !== owner || !owner.stage.acceptsCommands) throw CancellationException("Expired placement")
                        }
                        switchPlacement(owner.id, mode)
                        synchronized(lock) {
                            if (active === owner && owner.stage.acceptsCommands) {
                                owner.mode = mode
                                owner.placementGeneration++
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (active === owner && owner.stage.acceptsCommands) {
                            owner.pauses.add(PauseReason.USER)
                            owner.placementError = "切换未完成，已暂停；请检查设备状态后重试"
                            // A partial migration invalidates observations even when no new mode is published.
                            owner.placementGeneration++
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (active === owner) {
                            owner.placementRequest = null
                            refreshExecutionStageLocked(owner)
                        }
                    }
                }
            }.also { owner.placementJob = it }
        }
        job.start()
        return true
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
        finally { owner.placementJob?.cancel(); owner.job.cancel() }
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
            executionMode = owner.mode,
            placementGeneration = owner.placementGeneration,
            placementSwitching = owner.placementRequest != null,
            placementError = owner.placementError,
        )
    }

    private inner class Control(private val owner: ActiveRun) : RunControl {
        override val runId: String get() = owner.id
        override val executionMode: ExecutionMode get() = synchronized(lock) { owner.mode }
        override val placementGeneration: Long get() = synchronized(lock) { owner.placementGeneration }

        override suspend fun <T> deviceOperation(expectedGeneration: Long?, block: suspend () -> T): T {
            while (true) {
                checkpoint()
                deviceGate.lock()
                val ready = synchronized(lock) {
                    active === owner && owner.stage.canExecute && owner.placementRequest == null
                }
                if (!ready) { deviceGate.unlock(); continue }
                try {
                    if (expectedGeneration != null && expectedGeneration != placementGeneration) throw ExecutionTargetChangedException()
                    return block()
                } finally { deviceGate.unlock() }
            }
        }

        override suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            mutableSnapshot.first {
                synchronized(lock) {
                    active !== owner || (owner.placementRequest == null && owner.stage !in setOf(RunPhase.PAUSED, RunPhase.WAITING_INTERACTION))
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
                    thinking = "", steps = state.steps + RunStep(progress.number))
                is RunProgress.Thinking -> mutableSnapshot.value = state.copy(thinking = progress.text,
                    steps = state.steps.mapIndexed { index, step -> if (index == state.steps.lastIndex) step.copy(thinking = progress.text) else step })
                is RunProgress.Action -> mutableSnapshot.value = state.copy(currentAction = progress.description,
                    steps = state.steps.mapIndexed { index, step -> if (index == state.steps.lastIndex) step.copy(action = progress.description) else step })
                is RunProgress.UserMessage -> mutableMessages.value = mutableMessages.value +
                    ChatMessage(ChatRole.USER, progress.text, progress.timestamp, progress.images)
                is RunProgress.AssistantMessage -> mutableMessages.value = mutableMessages.value + ChatMessage(ChatRole.AGENT, progress.text, progress.timestamp)
                is RunProgress.Usage -> mutableSnapshot.value = state.copy(totalInputTokens = progress.totalInputTokens, totalOutputTokens = progress.totalOutputTokens)
                is RunProgress.ConversationChanged -> mutableSnapshot.value = state.copy(conversationId = progress.id)
                RunProgress.DeviceToolsRequested -> {
                    if (!state.deviceToolsRequested) mutableSnapshot.value = state.copy(deviceToolsRequested = true)
                    mutableDeviceToolRequests.tryEmit(owner.id)
                    Unit
                }
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
