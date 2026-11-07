package com.kevinluo.autoglm.task

/** Task effects are valid only for the task still represented by the current projection. */
internal object RunEventIdentity {
    fun accepts(eventRunId: String, currentRunId: String?, requiresActive: Boolean = false, active: Boolean = false): Boolean =
        eventRunId == currentRunId && (!requiresActive || active)
}
