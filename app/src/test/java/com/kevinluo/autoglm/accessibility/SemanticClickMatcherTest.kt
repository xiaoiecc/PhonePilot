package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SemanticClickMatcherTest {
    private fun list(id: String = "list", viewId: String = "test:id/list") = SemanticNode(
        id, SemanticKind.SCROLLER, label = "Conversations", viewId = viewId,
        actions = listOf(SemanticAction("scroll_forward")),
    )
    private fun row(id: String, description: String, parent: String = "list") = SemanticNode(
        id, SemanticKind.BUTTON, label = description, description = description, viewId = "test:id/row",
        actions = listOf(SemanticAction("click")), parentId = parent,
    )
    private fun source() = UiSnapshot("source", "", listOf(list(), row("a", "Alpha, old preview"), row("b", "Beta, hello")), 7)
    private fun fresh() = UiSnapshot("fresh", "", listOf(list(), row("new0", "Beta, new preview"), row("new1", "Alpha, changed preview")), 7)
    private fun resolve(
        before: UiSnapshot = source(), after: UiSnapshot = fresh(), node: String = "a",
        query: String = "Alpha, ", mode: SemanticMatchMode = SemanticMatchMode.PREFIX,
        beforeComplete: Boolean = true, afterComplete: Boolean = true,
    ) = SemanticClickMatcher.resolve(before, after, node, query, mode, beforeComplete, afterComplete)
    private fun assertRejected(result: SemanticClickResolution) = assertTrue(result is SemanticClickResolution.Rejected, result.toString())

    @Test fun `explicit stable prefix follows reordered row and changed preview in identified list`() {
        assertEquals(SemanticClickResolution.Matched("new1"), resolve())
        // A list widget may move in the tree; its uniquely matching identity is retained.
        val moved = fresh().copy(nodes = fresh().nodes.map {
            if (it.id == "list") it.copy(id = "moved-list") else it.copy(parentId = "moved-list")
        })
        assertEquals(SemanticClickResolution.Matched("new1"), resolve(after = moved))
    }

    @Test fun `exact mode matches literal whole labels and never treats regex or substring as patterns`() {
        val unchanged = source().copy(id = "fresh")
        assertEquals(SemanticClickResolution.Matched("a"), resolve(after = unchanged,
            query = "Alpha, old preview", mode = SemanticMatchMode.EXACT))
        listOf("Alpha", "alpha, old preview", "Alpha.*", "old preview", " Alpha, old preview", "Alpha, old preview ").forEach {
            assertRejected(resolve(after = unchanged, query = it, mode = SemanticMatchMode.EXACT))
        }
        assertRejected(resolve(mode = SemanticMatchMode.EXACT))
    }

    @Test fun `unsafe prefixes are rejected and explicit boundaries are required`() {
        listOf("Alpha", "Al", "Alpha ", ",", "   ", "Alpha.*").forEach {
            assertNotNull(SemanticClickMatcher.queryError(it, SemanticMatchMode.PREFIX), it)
        }
        listOf("Name, ", "姓名，", "Name:", "Name;", "Name\n", "Name\t").forEach {
            assertNull(SemanticClickMatcher.queryError(it, SemanticMatchMode.PREFIX), it)
        }
    }

    @Test fun `duplicate names in either original or current list are rejected`() {
        assertRejected(resolve(after = fresh().copy(nodes = fresh().nodes + row("duplicate", "Alpha, another preview"))))
        assertRejected(resolve(before = source().copy(nodes = source().nodes + row("duplicate", "Alpha, another preview"))))
    }

    @Test fun `missing row or row moved into a different list is rejected`() {
        assertRejected(resolve(after = fresh().copy(nodes = fresh().nodes.filterNot { it.id == "new1" })))
        val moved = fresh().copy(nodes = fresh().nodes.map {
            if (it.id == "new1") it.copy(parentId = "other-list") else it
        } + list("other-list", "test:id/other-list"))
        assertRejected(resolve(after = moved))
    }

    @Test fun `missing ambiguous renamed or unidentifiable scroll container is rejected`() {
        assertRejected(resolve(after = fresh().copy(nodes = fresh().nodes.filterNot { it.id == "list" })))
        assertRejected(resolve(after = fresh().copy(nodes = fresh().nodes + list("duplicate"))))
        assertRejected(resolve(after = fresh().copy(nodes = fresh().nodes.map {
            if (it.id == "list") it.copy(label = "Different context") else it
        })))
        assertRejected(resolve(before = source().copy(nodes = source().nodes.map {
            if (it.id == "list") it.copy(viewId = null) else it
        })))
    }

    @Test fun `changed window truncated traversal and invalid original node cannot match`() {
        assertRejected(resolve(after = fresh().copy(windowId = 8)))
        assertRejected(resolve(before = source().copy(windowId = null), after = fresh().copy(windowId = null)))
        assertRejected(resolve(beforeComplete = false))
        assertRejected(resolve(afterComplete = false))
        assertRejected(resolve(node = "unknown"))
        assertRejected(resolve(query = "Beta, "))
    }

    @Test fun `protected disabled wrong-kind wrong-id and nonclickable candidates are rejected`() {
        val original = source().nodes.first { it.id == "a" }
        listOf(original.copy(sensitive = true), original.copy(states = setOf("disabled")),
            original.copy(actions = emptyList())).forEach { changed ->
            assertRejected(resolve(before = source().copy(nodes = source().nodes.map { if (it.id == "a") changed else it })))
        }
        val candidate = fresh().nodes.first { it.id == "new1" }
        listOf(candidate.copy(sensitive = true), candidate.copy(states = setOf("disabled")),
            candidate.copy(actions = emptyList()), candidate.copy(kind = SemanticKind.TEXT),
            candidate.copy(viewId = "test:id/other-row")).forEach { changed ->
            assertRejected(resolve(after = fresh().copy(nodes = fresh().nodes.map { if (it.id == "new1") changed else it })))
        }
    }
}
