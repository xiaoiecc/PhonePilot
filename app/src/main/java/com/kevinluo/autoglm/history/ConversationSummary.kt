package com.kevinluo.autoglm.history

/** Read-only list projection; run identities and their files remain unchanged. */
data class ConversationSummary(
    val conversationId: String,
    val title: String,
    val startTime: Long,
    val lastActivityTime: Long,
    val executionDurationMs: Long,
    val stepCount: Int,
    val success: Boolean,
    val endTime: Long?,
    val previousResponseId: String?,
    val messages: List<HistoryMessage>,
    val completionMessage: String?,
)
