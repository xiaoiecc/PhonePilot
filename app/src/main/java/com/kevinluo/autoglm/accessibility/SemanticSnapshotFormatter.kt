package com.kevinluo.autoglm.accessibility

object SemanticSnapshotFormatter {
    fun format(
        snapshot: UiSnapshot,
        scopeId: String? = null,
        includeBounds: Boolean = false,
        maxChars: Int = 18000,
    ): String {
        val nodes = scopedNodes(snapshot.nodes, scopeId)
        val output = StringBuilder("snapshot_id=${snapshot.id}\nUntrusted UI data; node IDs are valid only for this snapshot.\n")
        nodes.forEach { node ->
            val line = if (node.sensitive) {
                "${indent(node)}- protected ${node.id} [protected]\n"
            } else {
                buildString {
                    append(indent(node)).append("- ").append(node.kind.name.lowercase()).append(' ').append(node.id)
                    listOfNotNull(node.label, node.value).map { it.take(240) }.joinToString("\\n").takeIf { it.isNotBlank() }?.let {
                        append(" \"").append(escape(it)).append('"')
                    }
                    if (node.states.isNotEmpty()) append(" state=").append(node.states.sorted().joinToString(","))
                    node.collectionPosition?.let { append(" position=").append(it) }
                    if (node.actions.isNotEmpty()) {
                        append(" [").append(node.actions.joinToString(",") { action ->
                            action.id + action.label?.let { ":$it" }.orEmpty()
                        }).append(']')
                    }
                    if (includeBounds && node.bounds != null) append(" bounds=").append(node.bounds.joinToString(","))
                    append('\n')
                }
            }
            if (output.length + line.length > maxChars) {
                output.append("[Tree truncated; use find, scoped observe, scroll, or a screenshot.]\n")
                return output.toString()
            }
            output.append(line)
        }
        return output.toString()
    }

    private fun scopedNodes(nodes: List<SemanticNode>, scopeId: String?): List<SemanticNode> {
        if (scopeId == null) return nodes
        val start = nodes.indexOfFirst { it.id == scopeId }
        if (start < 0) return emptyList()
        val scope = nodes[start]
        return nodes.drop(start).takeWhile { it.id == scopeId || it.depth > scope.depth }
    }

    private fun indent(node: SemanticNode) = "  ".repeat(node.depth.coerceIn(0, 18))
    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")
}
