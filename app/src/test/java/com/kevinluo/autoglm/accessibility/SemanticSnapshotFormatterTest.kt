package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticSnapshotFormatterTest {
    @Test
    fun `scroll capabilities are explicit without claiming remaining content or altering identities`() {
        val node = SemanticNode("list", SemanticKind.SCROLLER, label = "Conversations",
            actions = listOf(SemanticAction("scroll_forward"), SemanticAction("scroll_up"), SemanticAction("scroll_right")))
        val snapshot = UiSnapshot("s", "", listOf(node))
        val text = SemanticSnapshotFormatter.format(snapshot)
        assertTrue(text.contains("scroll_container=true reported_scroll=forward,up,right"))
        assertFalse(text.contains("end reached"))
        assertEquals(listOf(node), snapshot.nodes)
        val noActions = SemanticSnapshotFormatter.format(UiSnapshot("s", "", listOf(node.copy(actions = emptyList()))))
        assertTrue(noActions.contains("reported_scroll=none_reported"))
        assertFalse(noActions.contains("cannot scroll"))
    }

    @Test
    fun `empty layout wrappers fold only in presentation and retain original actionable node IDs`() {
        val wrapper = SemanticNode("wrapper", SemanticKind.GROUP)
        val list = SemanticNode("list", SemanticKind.SCROLLER, parentId = "wrapper", depth = 1,
            actions = listOf(SemanticAction("scroll_down")))
        val row = SemanticNode("row", SemanticKind.BUTTON, label = "Project group, latest preview",
            parentId = "list", depth = 2, actions = listOf(SemanticAction("click")))
        val snapshot = UiSnapshot("s", "", listOf(wrapper, list, row))
        val text = SemanticSnapshotFormatter.format(snapshot)
        assertFalse(text.contains("group wrapper"))
        assertTrue(text.contains("\n- scroller list"))
        assertTrue(text.contains("\n  - button row"))
        assertEquals("wrapper", snapshot.nodes[1].parentId)
        assertEquals("list", snapshot.nodes[2].parentId)
        assertEquals("Project group, latest preview", snapshot.nodes[2].label)
        assertTrue(SemanticSnapshotFormatter.format(snapshot, includeBounds = true).contains("group wrapper"))
    }

    @Test
    fun `labels under one actionable ancestor become one target`() {
        val nodes = listOf(
            SemanticNode("n1", SemanticKind.BUTTON, depth = 0, actions = listOf(SemanticAction("click")), childIds = listOf("n2", "n3")),
            SemanticNode("n2", SemanticKind.TEXT, label = "Alarms", parentId = "n1", depth = 1),
            SemanticNode("n3", SemanticKind.TEXT, label = "0 items", parentId = "n1", depth = 1),
        )
        val merged = SemanticNodeMerger.merge(nodes)
        val text = SemanticSnapshotFormatter.format(UiSnapshot("s", "", nodes = merged))
        assertTrue(text.contains("button n1"))
        assertTrue(text.contains("Alarms\\n0 items"))
        assertFalse(text.contains("n2"))
        assertFalse(text.contains("n3"))
    }

    @Test
    fun `states actions and collection context survive compact formatting`() {
        val node = SemanticNode(
            id = "n4", kind = SemanticKind.SWITCH, label = "Auto connect", depth = 1,
            states = setOf("off", "disabled", "focused"), collectionPosition = "row 2 of 8",
            actions = listOf(SemanticAction("click"), SemanticAction("custom_99", "Enable automatically")),
            bounds = intArrayOf(1, 2, 30, 40),
        )
        val text = SemanticSnapshotFormatter.format(UiSnapshot("s", "", nodes = listOf(node)))
        assertTrue(text.contains("switch n4"))
        assertTrue(text.contains("Auto connect"))
        assertTrue(text.contains("off"))
        assertTrue(text.contains("disabled"))
        assertTrue(text.contains("focused"))
        assertTrue(text.contains("row 2 of 8"))
        assertTrue(text.contains("Enable automatically"))
        assertFalse(text.contains("1,2,30,40"))
        val withBounds = SemanticSnapshotFormatter.format(UiSnapshot("s", "", nodes = listOf(node)), includeBounds = true)
        assertTrue(withBounds.contains("bounds=1,2,30,40"))
    }

    @Test
    fun `protected nodes never expose text or custom actions`() {
        val node = SemanticNode(
            "n5", SemanticKind.INPUT, label = "secret", value = "password",
            sensitive = true, actions = listOf(SemanticAction("set_text")),
        )
        val text = SemanticSnapshotFormatter.format(UiSnapshot("s", "", nodes = listOf(node)))
        assertTrue(text.contains("protected"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("password"))
        assertFalse(text.contains("set_text"))
    }

    @Test
    fun `nested interactive targets keep their own labels and nearest parent`() {
        val nodes = listOf(
            SemanticNode("row", SemanticKind.BUTTON, depth = 0, actions = listOf(SemanticAction("click"))),
            SemanticNode("title", SemanticKind.TEXT, label = "Alarm", parentId = "row", depth = 1),
            SemanticNode("delete", SemanticKind.BUTTON, label = "Delete", parentId = "row", depth = 1, actions = listOf(SemanticAction("click"))),
            SemanticNode("delete-icon", SemanticKind.TEXT, label = "Trash icon", parentId = "delete", depth = 2),
            SemanticNode("input", SemanticKind.INPUT, label = "Host", value = "192.168.1.1", depth = 0, actions = listOf(SemanticAction("set_text"))),
        )
        val merged = SemanticNodeMerger.merge(nodes)
        val row = merged.single { it.id == "row" }
        val delete = merged.single { it.id == "delete" }
        val input = merged.single { it.id == "input" }
        assertEquals("Alarm", row.label)
        assertEquals("Delete\nTrash icon", delete.label)
        assertFalse(merged.any { it.id == "title" })
        assertFalse(merged.any { it.id == "delete-icon" })
        assertEquals("row", delete.parentId)
        assertEquals("192.168.1.1", input.value)
    }
}
