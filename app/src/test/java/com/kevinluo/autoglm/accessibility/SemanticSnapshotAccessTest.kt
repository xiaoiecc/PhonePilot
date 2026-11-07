package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SemanticSnapshotAccessTest {
    @Test fun `scoped reads require current snapshot and a nonprotected known node`() {
        val snapshot = UiSnapshot("new", "", listOf(
            SemanticNode("scope", SemanticKind.GROUP),
            SemanticNode("secret", SemanticKind.INPUT, sensitive = true),
        ))
        assertNull(SemanticSnapshotAccess.scopeError(snapshot, "new", "scope", true))
        assertNotNull(SemanticSnapshotAccess.scopeError(snapshot, "old", "scope", true))
        assertNotNull(SemanticSnapshotAccess.scopeError(snapshot, "new", "scope", false))
        assertNotNull(SemanticSnapshotAccess.scopeError(snapshot, null, "scope", true))
        assertNotNull(SemanticSnapshotAccess.scopeError(snapshot, "new", "missing", true))
        assertNotNull(SemanticSnapshotAccess.scopeError(snapshot, "new", "secret", true))
    }

    @Test fun `find can reach captured nodes beyond short wire observation`() {
        val nodes = (0 until 600).map { index ->
            SemanticNode("n$index", SemanticKind.BUTTON, label = "Item $index " + "x".repeat(80), actions = listOf(SemanticAction("click")))
        }
        val snapshot = UiSnapshot("s", "", nodes)
        val shortObservation = SemanticSnapshotFormatter.format(snapshot, maxChars = 18000)
        assertFalse(shortObservation.contains("button n599"))
        val matches = SemanticFinder.find(snapshot, "Item 599")
        assertEquals("n599", matches.single().id)
        assertTrue(PhoneAccessibilityService.MAX_NODES > 600)
    }

    @Test fun `scoped formatter includes subtree and excludes unrelated nodes`() {
        val snapshot = UiSnapshot("s", "", listOf(
            SemanticNode("scope", SemanticKind.GROUP, depth = 0),
            SemanticNode("target", SemanticKind.BUTTON, label = "Inside", parentId = "scope", depth = 1),
            SemanticNode("other", SemanticKind.BUTTON, label = "Outside", depth = 0),
        ))
        val text = SemanticSnapshotFormatter.format(snapshot, scopeId = "scope")
        assertTrue(text.contains("Inside"))
        assertFalse(text.contains("Outside"))
    }

    @Test fun `action feedback retains unchanged actionable nodes in the new snapshot`() {
        val before = UiSnapshot("before", "", listOf(
            SemanticNode("a", SemanticKind.TEXT, label = "Before"),
            SemanticNode("b", SemanticKind.BUTTON, label = "Next", actions = listOf(SemanticAction("click"))),
        ), 1)
        val after = before.copy(id = "after", nodes = before.nodes.map { if (it.id == "a") it.copy(label = "After") else it })
        val text = SemanticActionFeedback.format(before, after)
        assertTrue(text.contains("snapshot_id=after"))
        assertTrue(text.contains("Next"))
        assertTrue(text.contains("[click]"))
        assertTrue(text.contains("unchanged targets"))
        assertFalse(text.contains("snapshot_id=before"))
    }

    @Test fun `action feedback is bounded and excludes protected content`() {
        val nodes = (0 until 800).map { SemanticNode("n$it", SemanticKind.TEXT, label = "x".repeat(500)) } +
            SemanticNode("secret", SemanticKind.INPUT, label = "private-password", sensitive = true)
        val text = SemanticActionFeedback.format(null, UiSnapshot("s", "", nodes))
        assertTrue(text.length <= 18000)
        assertFalse(text.contains("private-password"))
    }
}
