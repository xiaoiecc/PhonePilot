package com.kevinluo.autoglm.ui.capsule

import com.kevinluo.autoglm.core.RunPhase
import com.kevinluo.autoglm.core.RunSnapshot

/** Pure presentation projection. It never admits, resumes or executes a task. */
internal data class CapsulePresentation(
    val bodyVisible: Boolean,
    val inputVisible: Boolean,
    val interactionVisible: Boolean,
    val waitVisible: Boolean,
    val messagesVisible: Boolean,
    val pauseVisible: Boolean,
    val resumeVisible: Boolean,
    val stopVisible: Boolean,
    val newConversationVisible: Boolean,
) {
    companion object {
        /** Reuse the original full chat bubble whenever it already contains this description. */
        fun standaloneDescription(action: String, assistantTexts: Sequence<String>): String? =
            action.takeIf { text -> text.isNotBlank() && assistantTexts.none { it.trim() == text.trim() } }

        fun from(run: RunSnapshot, minimized: Boolean, details: Boolean, hasDraft: Boolean): CapsulePresentation {
            val ending = run.phase in setOf(RunPhase.CANCELLING, RunPhase.FINALIZING)
            val terminal = !run.phase.isActive
            val pending = run.pendingInteraction != null
            val input = !pending && !ending && (run.phase == RunPhase.PAUSED || terminal)
            val expanded = !minimized && (details || input || pending || run.activeWait != null || hasDraft)
            return CapsulePresentation(
                bodyVisible = expanded,
                inputVisible = expanded && input,
                interactionVisible = expanded && pending,
                waitVisible = expanded && run.activeWait != null,
                messagesVisible = expanded && (details || terminal),
                pauseVisible = !minimized && run.phase in setOf(RunPhase.STARTING, RunPhase.RUNNING),
                resumeVisible = !minimized && run.phase == RunPhase.PAUSED,
                stopVisible = !minimized && run.phase.isActive,
                newConversationVisible = expanded && run.phase in setOf(RunPhase.COMPLETED, RunPhase.FAILED),
            )
        }
    }
}
