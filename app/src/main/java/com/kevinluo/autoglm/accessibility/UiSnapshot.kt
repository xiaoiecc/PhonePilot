package com.kevinluo.autoglm.accessibility

data class UiSnapshot(
    val id: String,
    val text: String,
    val nodes: List<SemanticNode> = emptyList(),
    val windowId: Int? = null,
)

internal class SnapshotLease {
    var generation: Long = 0
        private set
    private var snapshotId: String? = null
    private var capturedGeneration = -1L
    private var invalidation = 0L
    private var capturedInvalidation = -1L

    fun invalidate() {
        generation++
        invalidation++
    }

    /** Platform events are hints, not proof that the observed semantic context changed. */
    fun markDirty() { generation++ }

    fun capture(id: String) {
        snapshotId = id
        capturedGeneration = generation
        capturedInvalidation = invalidation
    }

    fun isCurrent(id: String?): Boolean =
        canRevalidate(id) && capturedGeneration == generation

    fun canRevalidate(id: String?): Boolean =
        id != null && id == snapshotId && capturedInvalidation == invalidation

    /** Called only after a fresh full-context comparison; cannot revive an invalidated lease. */
    fun revalidate(id: String?): Boolean {
        if (!canRevalidate(id)) return false
        capturedGeneration = generation
        return true
    }
}
