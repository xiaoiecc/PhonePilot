package com.kevinluo.autoglm.accessibility

enum class SemanticKind { BUTTON, INPUT, SWITCH, CHECKBOX, SCROLLER, TEXT, GROUP, CUSTOM }

data class SemanticAction(val id: String, val label: String? = null)

data class SemanticNode(
    val id: String,
    val kind: SemanticKind,
    val label: String? = null,
    val value: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val states: Set<String> = emptySet(),
    val collectionPosition: String? = null,
    val actions: List<SemanticAction> = emptyList(),
    val parentId: String? = null,
    val childIds: List<String> = emptyList(),
    val depth: Int = 0,
    val bounds: IntArray? = null,
    val sensitive: Boolean = false,
)

object SemanticNodeMerger {
    fun merge(nodes: List<SemanticNode>): List<SemanticNode> {
        val byId = nodes.associateBy { it.id }
        val removed = mutableSetOf<String>()
        val merged = nodes.toMutableList()
        nodes.forEachIndexed { index, node ->
            if (!node.isInteractive()) return@forEachIndexed
            val descendants = descendants(node, byId).filter { !it.sensitive }
            val parts = listOfNotNull(node.label) +
                (if (node.kind == SemanticKind.INPUT) emptyList() else listOfNotNull(node.value)) +
                descendants.mapNotNull { it.label ?: it.value }
            val label = parts.flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .joinToString("\n").takeIf { it.isNotBlank() }
            if (label != null) merged[index] = node.copy(label = label, value = if (node.kind == SemanticKind.INPUT) node.value else null)
            descendants.forEach { removed += it.id }
        }
        return merged.filterNot { it.id in removed }.map { node ->
            var parent = node.parentId
            while (parent != null && parent in removed) parent = byId[parent]?.parentId
            node.copy(parentId = parent)
        }
    }

    private fun descendants(node: SemanticNode, byId: Map<String, SemanticNode>): List<SemanticNode> =
        byId.values.filter { it.parentId == node.id }.flatMap { child ->
            if (child.isInteractive()) emptyList() else listOf(child) + descendants(child, byId)
        }

    private fun SemanticNode.isInteractive(): Boolean = actions.isNotEmpty() ||
        kind in setOf(SemanticKind.BUTTON, SemanticKind.INPUT, SemanticKind.SWITCH, SemanticKind.CHECKBOX)
}
