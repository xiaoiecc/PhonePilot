package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SemanticSnapshotIdentityTest {
    private fun screen(id: String = "observed") = UiSnapshot(id, "display text", listOf(
        SemanticNode("question", SemanticKind.TEXT, label = "Question one"),
        SemanticNode("skip", SemanticKind.BUTTON, label = "Skip free trial",
            actions = listOf(SemanticAction("click")), bounds = intArrayOf(10, 100, 200, 150)),
    ), windowId = 7)

    @Test fun `event churn with unchanged semantic context keeps observed identity usable`() {
        val lease = SnapshotLease()
        val observed = screen()
        lease.capture(observed.id)
        repeat(50) { lease.markDirty() }
        assertFalse(lease.isCurrent(observed.id))
        // Fresh platform nodes have distinct IntArray instances and formatted text is not identity.
        val fresh = screen("internal-read").copy(text = "different formatting")
        assertTrue(SemanticSnapshotIdentity.unchanged(observed, fresh))
        assertTrue(lease.revalidate(observed.id))
        assertTrue(lease.isCurrent(observed.id))
        assertFalse(lease.isCurrent(fresh.id))
    }

    @Test fun `unchanged clicked node does not excuse changed question elsewhere`() {
        val observed = screen()
        val changed = observed.copy(nodes = observed.nodes.map {
            if (it.id == "question") it.copy(label = "Question two") else it
        })
        assertFalse(SemanticSnapshotIdentity.unchanged(observed, changed))
    }

    @Test fun `same label and bounds in different window is not the same context`() {
        assertFalse(SemanticSnapshotIdentity.unchanged(screen(), screen().copy(windowId = 8)))
        assertFalse(SemanticSnapshotIdentity.unchanged(screen().copy(windowId = null), screen().copy(windowId = null)))
    }

    @Test fun `target movement privacy state capabilities and identity changes all reject`() {
        val observed = screen()
        val target = observed.nodes.last()
        listOf(
            target.copy(bounds = intArrayOf(10, 101, 200, 151)),
            target.copy(sensitive = true),
            target.copy(states = setOf("disabled")),
            target.copy(actions = emptyList()),
            target.copy(viewId = "different-id"),
            target.copy(kind = SemanticKind.INPUT),
            target.copy(description = "different description"),
            target.copy(parentId = "different-parent"),
        ).forEach { changed ->
            assertFalse(SemanticSnapshotIdentity.unchanged(observed,
                observed.copy(nodes = observed.nodes.dropLast(1) + changed)), changed.toString())
        }
        assertFalse(SemanticSnapshotIdentity.unchanged(observed, observed.copy(nodes = observed.nodes.dropLast(1))))
    }

    @Test fun `event revalidation never revives explicit invalidation or superseded observation`() {
        val lease = SnapshotLease()
        lease.capture("old")
        lease.markDirty()
        lease.invalidate()
        assertFalse(lease.canRevalidate("old"))
        assertFalse(lease.revalidate("old"))
        lease.capture("new")
        lease.markDirty()
        assertFalse(lease.revalidate("old"))
        assertFalse(lease.isCurrent("new"))
        assertTrue(lease.revalidate("new"))
        assertTrue(lease.isCurrent("new"))
    }
}
