package com.kevinluo.autoglm.accessibility

/** Snapshot identity and privacy checks shared by scoped reads and searches. */
internal object SemanticSnapshotAccess {
    fun scopeError(snapshot: UiSnapshot?, snapshotId: String?, scopeId: String, current: Boolean): String? = when {
        !current || snapshotId == null || snapshot == null || snapshot.id != snapshotId ->
            "ERROR: stale scope snapshot; call android_ui observe again."
        snapshot.nodes.none { it.id == scopeId && !it.sensitive } ->
            "ERROR: unknown or protected scope; observe again."
        else -> null
    }
}

/** Keep both the change explanation and actionable current context within the output budget. */
internal object SemanticActionFeedback {
    fun format(before: UiSnapshot?, after: UiSnapshot, maxChars: Int = 18000): String {
        val prefix = buildString {
            append("Action accepted by Android; verify the result below.\n")
            if (before != null) {
                val diff = SnapshotDiffer.diff(before, after)
                append(if (diff.windowChanged) "Window changed.\n" else diff.text.take(3000)).append('\n')
            }
            append("Current semantic observation: use its snapshot_id for all listed nodes, including unchanged targets.\n")
        }
        // Formatter may append its own truncation notice; reserve space for it.
        val observation = if (after.nodes.isEmpty()) after.text else
            SemanticSnapshotFormatter.format(after, maxChars = (maxChars - prefix.length - 160).coerceAtLeast(128))
        return (prefix + observation).take(maxChars)
    }
}
