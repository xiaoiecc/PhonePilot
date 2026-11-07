package com.kevinluo.autoglm.accessibility

object SemanticFinder {
    fun find(snapshot: UiSnapshot, query: String, scopeId: String? = null, limit: Int = 8): List<SemanticNode> {
        val normalized = query.trim().lowercase()
        require(normalized.isNotEmpty()) { "Search query is required" }
        val scoped = scope(snapshot.nodes, scopeId)
        return scoped.filter { !it.sensitive }
            .mapIndexed { index, node -> Triple(index, node, score(node, normalized)) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, SemanticNode, Int>> { it.third }.thenBy { it.first })
            .take(limit)
            .map { it.second }
    }

    fun formatMatches(snapshot: UiSnapshot, matches: List<SemanticNode>): String = buildString {
        append("snapshot_id=${snapshot.id}\n")
        if (matches.isEmpty()) append("No matching nodes in the current semantic snapshot.\n")
        matches.forEach { match ->
            append("- ").append(match.id).append(" path=").append(path(match, snapshot.nodes))
            match.collectionPosition?.let { append(" position=").append(it) }
            if (match.actions.isNotEmpty()) append(" actions=").append(match.actions.joinToString(",") { it.id })
            append('\n')
        }
    }

    private fun score(node: SemanticNode, query: String): Int {
        val labels = listOfNotNull(node.label, node.value, node.description, node.hint)
        return when {
            labels.any { it.equals(query, true) } -> 100
            labels.any { it.startsWith(query, true) } -> 80
            labels.any { it.contains(query, true) } -> 60
            node.viewId?.contains(query, true) == true -> 50
            node.actions.any { it.label?.contains(query, true) == true || it.id.contains(query, true) } -> 45
            node.states.any { it.contains(query, true) } -> 30
            else -> 0
        }
    }

    private fun scope(nodes: List<SemanticNode>, scopeId: String?): List<SemanticNode> {
        if (scopeId == null) return nodes
        val byId = nodes.associateBy { it.id }
        fun descendants(id: String): List<SemanticNode> =
            nodes.filter { it.parentId == id }.flatMap { listOf(it) + descendants(it.id) }
        return listOfNotNull(byId[scopeId]) + scopeId?.let(::descendants).orEmpty()
    }

    fun inScope(node: SemanticNode, snapshot: UiSnapshot, scopeId: String?): Boolean {
        if (scopeId == null) return true
        val byId = snapshot.nodes.associateBy { it.id }
        var current: SemanticNode? = node
        while (current != null) {
            if (current.id == scopeId) return true
            current = current.parentId?.let(byId::get)
        }
        return false
    }

    private fun path(node: SemanticNode, nodes: List<SemanticNode>): String {
        val byId = nodes.associateBy { it.id }
        val parts = mutableListOf<String>()
        var current: SemanticNode? = node
        while (current != null) {
            parts += current.label ?: current.value ?: current.id
            current = current.parentId?.let(byId::get)
        }
        return parts.asReversed().joinToString(" > ")
    }
}
