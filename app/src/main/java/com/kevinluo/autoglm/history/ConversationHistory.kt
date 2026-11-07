package com.kevinluo.autoglm.history

import com.kevinluo.autoglm.core.ConversationSnapshot

data class RestoredConversation(val session: ConversationSnapshot, val messages: List<HistoryMessage>)

/** Each run remains available for diagnostics; summaries and chats operate on a whole conversation. */
object ConversationHistory {
    fun runs(tasks: List<TaskHistory>, conversationId: String): List<TaskHistory> =
        tasks.filter { it.conversationId == conversationId }.asReversed().sortedBy { it.startTime }

    fun messages(runs: List<TaskHistory>): List<HistoryMessage> = runs.flatMap { task ->
        if (task.messages.isNotEmpty()) task.messages else buildList {
            add(HistoryMessage(HistoryMessageRole.USER, task.taskDescription, task.startTime))
            task.completionMessage?.takeIf { it.isNotBlank() && task.success }?.let {
                add(HistoryMessage(HistoryMessageRole.AGENT, it, task.endTime ?: task.startTime))
            }
        }
    }.sortedBy { it.timestamp }

    fun restore(tasks: List<TaskHistory>, conversationId: String): RestoredConversation? {
        val runs = runs(tasks, conversationId)
        val latest = runs.lastOrNull() ?: return null
        return RestoredConversation(
            ConversationSnapshot(conversationId, latest.previousResponseId,
                latest.totalInputTokens, latest.totalOutputTokens, latest.totalCachedTokens,
                latest.hasInputUsage, latest.hasOutputUsage),
            messages(runs),
        )
    }

    fun summaries(tasks: List<TaskHistory>, nowMs: Long = System.currentTimeMillis()): List<ConversationSummary> = tasks.groupBy { it.conversationId }.values.map { grouped ->
        val runs = grouped.asReversed().sortedBy { it.startTime }
        val latest = runs.last()
        val messages = messages(runs)
        ConversationSummary(latest.conversationId, runs.first().taskDescription, runs.first().startTime,
            runs.maxOf { run -> maxOf(run.startTime, run.endTime ?: run.startTime,
                run.messages.maxOfOrNull { it.timestamp } ?: run.startTime,
                run.steps.maxOfOrNull { it.timestamp } ?: run.startTime) },
            runs.sumOf { ((it.endTime ?: nowMs) - it.startTime).coerceAtLeast(0) },
            runs.sumOf { it.stepCount }, latest.success, latest.endTime, latest.previousResponseId,
            messages.toList(), latest.completionMessage)
    }.sortedByDescending { it.lastActivityTime }

    /** Renderer projection only, never persisted. Dates and original screenshot paths stay true. */
    fun detail(tasks: List<TaskHistory>, conversationId: String): TaskHistory? {
        val runs = runs(tasks, conversationId)
        val latest = runs.lastOrNull() ?: return null
        return latest.copy(taskDescription = runs.first().taskDescription, startTime = runs.first().startTime,
            endTime = if (runs.any { it.endTime == null }) null else runs.maxOf { it.endTime!! },
            steps = runs.flatMap { it.steps }.sortedBy { it.timestamp }.mapIndexed { index, step ->
                step.copy(stepNumber = index + 1)
            }.toMutableList(), messages = messages(runs).toMutableList(),
            toolCalls = runs.flatMap { it.toolCalls }.sortedBy { it.startedAt }.toMutableList())
    }
}
