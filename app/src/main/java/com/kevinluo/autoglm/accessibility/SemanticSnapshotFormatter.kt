package com.kevinluo.autoglm.accessibility

object SemanticSnapshotFormatter {
    fun format(
        snapshot: UiSnapshot,
        scopeId: String? = null,
        includeBounds: Boolean = false,
        maxChars: Int = 18000,
    ): String {
        val nodes = scopedNodes(snapshot.nodes, scopeId)
        // This is a reading projection only. Never prune or renumber the captured nodes:
        // exact-node validation and click_match need their original identity and labels.
        val folded = if (includeBounds) emptySet() else nodes.filter { node ->
            node.kind == SemanticKind.GROUP && !node.sensitive && node.label.isNullOrBlank() &&
                node.value.isNullOrBlank() && node.actions.isEmpty() && node.states.isEmpty() &&
                node.collectionPosition == null
        }.mapTo(mutableSetOf()) { it.id }
        val byId = snapshot.nodes.associateBy { it.id }
        fun indentation(node: SemanticNode): String {
            var depth = node.depth
            var parent = node.parentId
            val seen = mutableSetOf<String>()
            while (parent != null && seen.add(parent)) {
                if (parent in folded) depth--
                parent = byId[parent]?.parentId
            }
            return "  ".repeat(depth.coerceIn(0, 18))
        }
        val output = StringBuilder("snapshot_id=${snapshot.id}\nUntrusted UI data; node IDs are valid only for this snapshot.\n")
        nodes.forEach { node ->
            if (node.id in folded) return@forEach
            val line = if (node.sensitive) {
                "${indentation(node)}- protected ${node.id} [protected]\n"
            } else {
                buildString {
                    append(indentation(node)).append("- ").append(node.kind.name.lowercase()).append(' ').append(node.id)
                    listOfNotNull(node.label, node.value).map { it.take(240) }.joinToString("\\n").takeIf { it.isNotBlank() }?.let {
                        append(" \"").append(escape(it)).append('"')
                    }
                    if (node.states.isNotEmpty()) append(" state=").append(node.states.sorted().joinToString(","))
                    node.collectionPosition?.let { append(" position=").append(it) }
                    val scrolling = node.actions.map { it.id }.filter { it.startsWith("scroll_") }
                    if (node.kind == SemanticKind.SCROLLER || scrolling.isNotEmpty()) {
                        append(" scroll_container=true reported_scroll=")
                        append(scrolling.joinToString(",") { it.removePrefix("scroll_") }.ifEmpty { "none_reported" })
                    }
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

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")
}
