package com.kevinluo.autoglm.accessibility

/** Already decoded requests; no wire JSON or operation names enter the Android backend. */
sealed interface UiCommand {
    val readOnly: Boolean get() = this is Observe || this is Find && !searchScroll
    data class Observe(val snapshotId: String?, val scopeId: String?) : UiCommand
    data class Find(val query: String, val snapshotId: String?, val scopeId: String?, val searchScroll: Boolean) : UiCommand
    data class Target(val snapshotId: String, val nodeId: String, val action: NodeAction) : UiCommand
    data class ClickMatching(
        val snapshotId: String,
        val nodeId: String,
        val query: String,
        val matchMode: SemanticMatchMode = SemanticMatchMode.EXACT,
    ) : UiCommand
}

enum class SemanticMatchMode { EXACT, PREFIX }

sealed interface NodeAction {
    enum class Simple : NodeAction {
        CLICK, LONG_CLICK, SCROLL_FORWARD, SCROLL_BACKWARD,
        SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT, SHOW,
    }
    data class Text(val value: String, val append: Boolean) : NodeAction
    data class Selection(val start: Int, val end: Int) : NodeAction
    data class Progress(val value: Float) : NodeAction
    data class Custom(val id: Int) : NodeAction
}
