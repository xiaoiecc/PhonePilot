package com.kevinluo.autoglm.accessibility

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiSnapshotTest {
    @Test
    fun `password node exposes neither value description nor actions`() {
        val node = SemanticNode(
            "n0", SemanticKind.INPUT, label = "secret-password", value = "password-hint-secret",
            actions = listOf(SemanticAction("set_text")), sensitive = true,
        )
        val text = SemanticSnapshotFormatter.format(UiSnapshot("s", "", nodes = listOf(node)))
        assertTrue(text.contains("protected"))
        assertFalse(text.contains("secret-password"))
        assertFalse(text.contains("password-hint-secret"))
        assertFalse(text.contains("set_text"))
    }

    @Test
    fun `ui strings are escaped and bounded`() {
        val node = SemanticNode(
            "n0", SemanticKind.TEXT, label = "x".repeat(10000), value = "line\n\"quoted\"",
        )
        val text = SemanticSnapshotFormatter.format(UiSnapshot("s", "", nodes = listOf(node)))
        assertTrue(text.length < 600)
        assertTrue(text.contains("\\n\\\"quoted\\\""))
    }

    @Test
    fun `snapshot expires on screen change or new observation`() {
        val lease = SnapshotLease()
        assertFalse(lease.isCurrent(null))
        lease.capture("one")
        assertTrue(lease.isCurrent("one"))
        lease.invalidate()
        assertFalse(lease.isCurrent("one"))
        lease.capture("two")
        assertFalse(lease.isCurrent("one"))
        assertTrue(lease.isCurrent("two"))
    }
}
