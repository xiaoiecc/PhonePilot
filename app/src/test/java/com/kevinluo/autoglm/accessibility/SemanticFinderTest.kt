package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticFinderTest {
    @Test
    fun `find searches semantic fields and returns ranked matches with ancestor context`() {
        val nodes = listOf(
            SemanticNode("root", SemanticKind.GROUP, label = "Settings"),
            SemanticNode("item", SemanticKind.BUTTON, label = "Network", parentId = "root", depth = 1, actions = listOf(SemanticAction("click"))),
            SemanticNode("field", SemanticKind.INPUT, hint = "Proxy host", parentId = "root", depth = 1, viewId = "app:id/host"),
            SemanticNode("custom", SemanticKind.CUSTOM, label = "Other", parentId = "root", depth = 1, actions = listOf(SemanticAction("custom_1", "Pin to top"))),
        )
        val snapshot = UiSnapshot("s", "", nodes)
        assertEquals("field", SemanticFinder.find(snapshot, "proxy").single().id)
        assertEquals("field", SemanticFinder.find(snapshot, "app:id/host").single().id)
        assertEquals("custom", SemanticFinder.find(snapshot, "Pin to").single().id)
        val matches = SemanticFinder.find(snapshot, "network")
        assertEquals("item", matches.single().id)
        val text = SemanticFinder.formatMatches(snapshot, matches)
        assertTrue(text.contains("Settings"))
        assertTrue(text.contains("Network"))
    }

    @Test
    fun `scope and result limit are deterministic and protected nodes are excluded`() {
        val nodes = (0 until 10).map { index ->
            SemanticNode("n$index", SemanticKind.TEXT, label = "same", parentId = if (index < 5) "a" else "b")
        } + SemanticNode("a", SemanticKind.GROUP) + SemanticNode("b", SemanticKind.GROUP) +
            SemanticNode("secret", SemanticKind.INPUT, label = "same", sensitive = true)
        val snapshot = UiSnapshot("s", "", nodes)
        assertEquals(5, SemanticFinder.find(snapshot, "same", scopeId = "a").size)
        assertEquals(5, SemanticFinder.find(snapshot, "same", limit = 5).size)
        assertTrue(SemanticFinder.find(snapshot, "same").none { it.sensitive })
    }

    @Test
    fun `scope checks include only the node and its descendants`() {
        val nodes = listOf(
            SemanticNode("a", SemanticKind.GROUP),
            SemanticNode("child", SemanticKind.SCROLLER, parentId = "a"),
            SemanticNode("other", SemanticKind.SCROLLER),
        )
        val snapshot = UiSnapshot("s", "", nodes)
        assertTrue(SemanticFinder.inScope(nodes[1], snapshot, "a"))
        assertFalse(SemanticFinder.inScope(nodes[2], snapshot, "a"))
        assertTrue(SemanticFinder.inScope(nodes[2], snapshot, null))
    }
}
