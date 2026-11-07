package com.kevinluo.autoglm.accessibility

/** Safety identity, independent of display formatting, output truncation and fresh snapshot IDs. */
internal object SemanticSnapshotIdentity {
    fun unchanged(before: UiSnapshot, after: UiSnapshot): Boolean =
        before.windowId != null && before.windowId == after.windowId &&
            before.nodes.size == after.nodes.size && before.nodes.zip(after.nodes).all { (old, new) ->
                // IntArray uses referential equality in a data class. Compare its contents separately.
                old.copy(bounds = null) == new.copy(bounds = null) &&
                    when {
                        old.bounds == null -> new.bounds == null
                        new.bounds == null -> false
                        else -> old.bounds.contentEquals(new.bounds)
                    }
            }
}
