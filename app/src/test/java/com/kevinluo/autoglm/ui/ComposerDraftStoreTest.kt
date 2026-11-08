package com.kevinluo.autoglm.ui

import com.kevinluo.autoglm.core.ChatImage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComposerDraftStoreTest {
    private val image = ChatImage("/private/image.webp", "image/webp")

    @Test fun `picker result stays with original conversation after switching`() {
        val store = ComposerDraftStore()
        store.save("first", "first draft", emptyList())
        val ticket = store.ticket("first")
        store.save("second", "second draft", emptyList())
        assertTrue(store.append(ticket, listOf(image)))
        assertEquals(listOf(image), store.get("first").images)
        assertEquals("first draft", store.get("first").text)
        assertEquals(ComposerDraftStore.Draft("second draft"), store.get("second"))
    }

    @Test fun `send invalidates pending import without restoring attachments`() {
        val store = ComposerDraftStore()
        val ticket = store.ticket(null)
        store.clear(null)
        store.save(null, "new draft", emptyList())
        assertFalse(store.append(ticket, listOf(image)))
        assertEquals(ComposerDraftStore.Draft("new draft"), store.get(null))
        assertTrue(store.append(store.ticket(null), listOf(image)))
    }

    @Test fun `new conversation invalidates picker while preserving unsent draft`() {
        val store = ComposerDraftStore()
        store.save(null, "unsent", listOf(image))
        val ticket = store.ticket(null)
        store.invalidateImports(null)
        assertFalse(store.append(ticket, listOf(image)))
        assertEquals(ComposerDraftStore.Draft("unsent", listOf(image)), store.get(null))
    }

    @Test fun `draft owns copy of selected images`() {
        val store = ComposerDraftStore()
        val selected = mutableListOf(image)
        store.save("first", "", selected)
        selected.clear()
        assertEquals(listOf(image), store.get("first").images)
    }
}
