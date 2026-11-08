package com.kevinluo.autoglm.ui

import com.kevinluo.autoglm.core.ChatImage

/** Main-thread composer state. Import tickets belong to a draft, never to the current view. */
class ComposerDraftStore {
    data class Draft(val text: String = "", val images: List<ChatImage> = emptyList())
    data class ImportTicket(val conversationId: String?, val generation: Long)
    private val drafts = mutableMapOf<String?, Draft>()
    private val generations = mutableMapOf<String?, Long>()

    fun get(conversationId: String?): Draft = drafts[conversationId] ?: Draft()

    fun save(conversationId: String?, text: String, images: List<ChatImage>) {
        drafts[conversationId] = Draft(text, images.toList())
    }

    fun ticket(conversationId: String?) = ImportTicket(conversationId, generations[conversationId] ?: 0L)

    fun invalidateImports(conversationId: String?) {
        generations[conversationId] = (generations[conversationId] ?: 0L) + 1L
    }

    fun clear(conversationId: String?) {
        invalidateImports(conversationId)
        drafts[conversationId] = Draft()
    }

    fun append(ticket: ImportTicket, images: List<ChatImage>): Boolean {
        if (ticket.generation != (generations[ticket.conversationId] ?: 0L)) return false
        val draft = get(ticket.conversationId)
        drafts[ticket.conversationId] = draft.copy(images = draft.images + images)
        return true
    }
}
