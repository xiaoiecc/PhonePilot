package com.kevinluo.autoglm.accessibility

internal sealed interface SemanticClickResolution {
    data class Matched(val nodeId: String) : SemanticClickResolution
    data class Rejected(val reason: String) : SemanticClickResolution
}

/** Explicit literal matching inside one identified list; never fuzzy-searches or guesses identity. */
internal object SemanticClickMatcher {
    fun queryError(query: String, mode: SemanticMatchMode): String? {
        if (query.length > 1000) return "The literal query is limited to 1000 characters."
        val literal = literal(query, mode)
        if (literal.isEmpty() || !literal.any(Char::isLetterOrDigit)) return "A literal query containing a letter or digit is required."
        if (mode == SemanticMatchMode.PREFIX && literal.last() !in ",，:：;；\n\t") {
            return "Prefix matching requires an explicit trailing name boundary: comma, colon, semicolon, newline or tab."
        }
        return null
    }

    fun resolve(
        source: UiSnapshot,
        fresh: UiSnapshot,
        sourceNodeId: String,
        query: String,
        mode: SemanticMatchMode,
        sourceComplete: Boolean,
        freshComplete: Boolean,
    ): SemanticClickResolution {
        fun reject(reason: String) = SemanticClickResolution.Rejected(reason)
        queryError(query, mode)?.let { return reject(it) }
        if (!sourceComplete || !freshComplete) return reject("A complete accessibility traversal is required; observe or use a screenshot.")
        if (source.windowId == null || source.windowId != fresh.windowId) return reject("The selected window changed; observe again.")
        val original = source.nodes.singleOrNull { it.id == sourceNodeId }
            ?: return reject("The originally observed node is unavailable; observe again.")
        if (!clickable(original) || !matches(original, query, mode)) {
            return reject("The literal query must match the originally observed clickable node.")
        }
        val scope = nearestScroller(original, source)
            ?: return reject("The observed node needs an identified scroll container; use a screenshot.")
        if (scope.sensitive || "disabled" in scope.states || scope.viewId.isNullOrBlank()) {
            return reject("The observed scroll container is protected or has no stable view ID.")
        }
        fun sameScope(node: SemanticNode) = node.kind == scope.kind && node.viewId == scope.viewId &&
            (scope.label.isNullOrBlank() || node.label == scope.label) && !node.sensitive && "disabled" !in node.states
        if (source.nodes.count(::sameScope) != 1) return reject("The original scroll container is ambiguous.")
        if (source.nodes.count { candidate ->
            candidate.kind == original.kind && candidate.viewId == original.viewId && clickable(candidate) &&
                nearestScroller(candidate, source)?.id == scope.id && matches(candidate, query, mode)
        } != 1) return reject("The literal query is ambiguous in the original scroll container.")
        val freshScope = fresh.nodes.singleOrNull(::sameScope)
            ?: return reject("The current scroll container is missing or ambiguous; observe again.")
        val candidates = fresh.nodes.filter { candidate ->
            candidate.kind == original.kind && candidate.viewId == original.viewId && clickable(candidate) &&
                nearestScroller(candidate, fresh)?.id == freshScope.id && matches(candidate, query, mode)
        }
        val resolved = candidates.singleOrNull()
            ?: return reject("The literal query matched ${candidates.size} current rows; exactly one is required. Observe again.")
        return SemanticClickResolution.Matched(resolved.id)
    }

    private fun clickable(node: SemanticNode): Boolean =
        !node.sensitive && "disabled" !in node.states && node.actions.any { it.id == "click" }

    private fun matches(node: SemanticNode, query: String, mode: SemanticMatchMode): Boolean {
        val literal = literal(query, mode)
        return listOfNotNull(node.label, node.description).any { value ->
            when (mode) {
                SemanticMatchMode.EXACT -> value == literal
                SemanticMatchMode.PREFIX -> value.startsWith(literal)
            }
        }
    }

    private fun literal(query: String, mode: SemanticMatchMode): String =
        if (mode == SemanticMatchMode.PREFIX) query.trimEnd(' ') else query

    private fun nearestScroller(node: SemanticNode, snapshot: UiSnapshot): SemanticNode? {
        val byId = snapshot.nodes.associateBy { it.id }
        val visited = mutableSetOf<String>()
        var current = node.parentId?.let(byId::get)
        while (current != null && visited.add(current.id)) {
            if (current.sensitive) return null
            if (current.kind == SemanticKind.SCROLLER) return current
            current = current.parentId?.let(byId::get)
        }
        return null
    }
}
