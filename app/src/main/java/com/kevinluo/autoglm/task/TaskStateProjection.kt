package com.kevinluo.autoglm.task

import com.kevinluo.autoglm.core.*
import com.kevinluo.autoglm.ui.TaskStatus
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Read-only projection, with no independently writable UI task state. */
data class TaskExecutionState(val run: RunSnapshot = RunSnapshot()) {
    val status: TaskStatus get() = when (run.phase) {
        RunPhase.IDLE -> TaskStatus.IDLE
        RunPhase.STARTING, RunPhase.RUNNING -> TaskStatus.RUNNING
        RunPhase.PAUSED -> TaskStatus.PAUSED
        RunPhase.CANCELLING, RunPhase.FINALIZING -> TaskStatus.CANCELLING
        RunPhase.COMPLETED -> TaskStatus.COMPLETED
        RunPhase.FAILED -> TaskStatus.FAILED
        RunPhase.WAITING_INTERACTION -> when (run.pendingInteraction?.kind) {
            InteractionKind.TAKEOVER -> TaskStatus.WAITING_TAKEOVER
            InteractionKind.TEXT -> TaskStatus.WAITING_FOR_USER
            else -> TaskStatus.WAITING_CONFIRMATION
        }
    }
    val runId get() = run.runId
    val stepNumber get() = run.stepNumber
    val thinking get() = run.thinking
    val currentAction get() = run.currentAction
    val resultMessage get() = run.resultMessage
    val conversationId get() = run.conversationId
    val totalInputTokens get() = run.totalInputTokens
    val totalOutputTokens get() = run.totalOutputTokens
    val pendingInteraction get() = run.pendingInteraction
    val isExecuting get() = run.phase.isActive
}

val TaskStatus.isActive: Boolean get() = this in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED,
    TaskStatus.CANCELLING, TaskStatus.WAITING_FOR_USER, TaskStatus.WAITING_CONFIRMATION, TaskStatus.WAITING_TAKEOVER)

/** A mapped StateFlow reads the source directly and needs no second cache or writer. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class ProjectedStateFlow<S, T>(private val source: StateFlow<S>, private val transform: (S) -> T) : StateFlow<T> {
    override val value: T get() = transform(source.value)
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow unexpectedly completed")
    }
}
