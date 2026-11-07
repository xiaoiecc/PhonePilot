package com.kevinluo.autoglm.accessibility

enum class SemanticChangeKind { ADDED, REMOVED, CHANGED }

data class SemanticChange(
    val nodeId: String,
    val kind: SemanticChangeKind,
    val before: String?,
    val after: String?,
)

data class SemanticDiff(val windowChanged: Boolean, val changes: List<SemanticChange>, val text: String)

object SnapshotDiffer {
    fun diff(before: UiSnapshot, after: UiSnapshot): SemanticDiff {
        if (before.windowId != after.windowId) {
            return SemanticDiff(true, emptyList(), "Window changed; use the full snapshot.\nsnapshot_id=${after.id}\n${after.text}")
        }
        val beforeNodes = before.nodes.associateBy { it.id }
        val afterNodes = after.nodes.associateBy { it.id }
        val changes = mutableListOf<SemanticChange>()
        (beforeNodes.keys + afterNodes.keys).forEach { id ->
            val old = beforeNodes[id]
            val new = afterNodes[id]
            when {
                old == null && new != null -> changes += SemanticChange(id, SemanticChangeKind.ADDED, null, signature(new))
                old != null && new == null -> changes += SemanticChange(id, SemanticChangeKind.REMOVED, signature(old), null)
                old != null && new != null && signature(old) != signature(new) ->
                    changes += SemanticChange(id, SemanticChangeKind.CHANGED, signature(old), signature(new))
            }
        }
        val text = buildString {
            append("snapshot_id=${after.id}\n")
            if (changes.isEmpty()) append("No semantic change detected.\n")
            changes.take(16).forEach { change ->
                append(when (change.kind) {
                    SemanticChangeKind.ADDED -> "+ "
                    SemanticChangeKind.REMOVED -> "- "
                    SemanticChangeKind.CHANGED -> "~ "
                }).append(change.nodeId)
                change.before?.let { append(" before=").append(it) }
                change.after?.let { append(" after=").append(it) }
                append('\n')
            }
            if (changes.size > 16) append("${changes.size - 16} more changes; use observe for details.\n")
        }
        return SemanticDiff(false, changes, text)
    }

    private fun signature(node: SemanticNode): String {
        if (node.sensitive) return "protected"
        return listOfNotNull(
            node.label, node.value, node.stateDescriptionForDiff(), node.collectionPosition,
            node.actions.joinToString(",") { it.id + it.label.orEmpty() },
        ).joinToString("|")
    }

    private fun SemanticNode.stateDescriptionForDiff(): String = states.sorted().joinToString(",")
}
