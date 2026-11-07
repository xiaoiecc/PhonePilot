package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnapshotDifferTest {
    @Test
    fun `diff reports added removed changed and unchanged semantic nodes`() {
        val before = UiSnapshot("s1", "", listOf(
            SemanticNode("same", SemanticKind.TEXT, label = "same"),
            SemanticNode("changed", SemanticKind.SWITCH, label = "WiFi", states = setOf("off")),
            SemanticNode("removed", SemanticKind.TEXT, label = "old"),
        ), windowId = 1)
        val after = UiSnapshot("s2", "", listOf(
            SemanticNode("same", SemanticKind.TEXT, label = "same"),
            SemanticNode("changed", SemanticKind.SWITCH, label = "WiFi", states = setOf("on")),
            SemanticNode("added", SemanticKind.TEXT, label = "new"),
        ), windowId = 1)
        val diff = SnapshotDiffer.diff(before, after)
        assertFalse(diff.windowChanged)
        assertEquals(setOf("added", "removed", "changed"), diff.changes.map { it.nodeId }.toSet())
        assertTrue(diff.text.contains("off"))
        assertTrue(diff.text.contains("on"))
        assertTrue(diff.text.contains("snapshot_id=s2"))
        assertTrue(SnapshotDiffer.diff(before, before).text.contains("No semantic change detected"))
    }

    @Test
    fun `window change requires a fresh full observation`() {
        val before = UiSnapshot("s1", "", windowId = 1)
        val after = UiSnapshot("s2", "", windowId = 2)
        assertTrue(SnapshotDiffer.diff(before, after).windowChanged)
    }
}
