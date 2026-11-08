package com.kevinluo.autoglm.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlaySnapshot(
    val userEnabled: Boolean = false,
    val appInForeground: Boolean = true,
    val visible: Boolean = false,
    val minimized: Boolean = false,
    val runId: String? = null,
    val runActive: Boolean = false,
    val deviceToolsRequested: Boolean = false,
    val completionVisible: Boolean = false,
    val hiddenLeaseCount: Int = 0,
    val pauseVisibilityCount: Int = 0,
)

/** One owner for presentation. It never starts, pauses, or completes an engine run. */
class OverlayPolicy {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(OverlaySnapshot())
    val snapshot: StateFlow<OverlaySnapshot> = mutableSnapshot.asStateFlow()
    private var generation = 0L
    private var leaseIds = 0L
    private val hidden = mutableMapOf<Long, Long>()
    private val pauseVisibility = mutableMapOf<Long, Long>()
    private var suppressedRun: String? = null

    fun enableUser() = synchronized(lock) { publish(mutableSnapshot.value.copy(userEnabled = true)) }

    fun disableUser() = synchronized(lock) {
        val state = mutableSnapshot.value
        publish(state.copy(userEnabled = false, completionVisible = state.completionVisible && state.runActive))
    }

    fun toggleUser() = synchronized(lock) {
        if (mutableSnapshot.value.userEnabled || mutableSnapshot.value.runActive) disableUser() else enableUser()
    }

    /** Close only the surface; the task owner separately accepts Pause/Stop commands. */
    fun close() = synchronized(lock) {
        generation++
        hidden.clear()
        pauseVisibility.clear()
        suppressedRun = mutableSnapshot.value.runId
        publish(mutableSnapshot.value.copy(userEnabled = false, completionVisible = false))
    }

    fun onAppForeground() = synchronized(lock) {
        publish(mutableSnapshot.value.copy(appInForeground = true, completionVisible = false))
    }

    fun onAppBackground() = synchronized(lock) { publish(mutableSnapshot.value.copy(appInForeground = false)) }

    fun setMinimized(minimized: Boolean) = synchronized(lock) { publish(mutableSnapshot.value.copy(minimized = minimized)) }

    fun onRunChanged(run: RunSnapshot) = synchronized(lock) {
        val old = mutableSnapshot.value
        val newRun = run.phase.isActive && run.runId != old.runId
        if (newRun) {
            generation++
            hidden.clear()
            pauseVisibility.clear()
            suppressedRun = null
        }
        val finished = old.runActive && !run.phase.isActive && old.runId == run.runId
        val completion = when {
            newRun -> false
            finished -> old.visible && suppressedRun != run.runId
            run.phase == RunPhase.IDLE -> false
            else -> old.completionVisible
        }
        publish(old.copy(runId = run.runId, runActive = run.phase.isActive,
            deviceToolsRequested = run.deviceToolsRequested, completionVisible = completion))
    }

    fun acquireHide(): OverlayLease = acquireLease(hidden)

    /** Temporarily expose controls while a checkpoint suspends, without releasing outer hides. */
    fun acquirePauseVisibility(): OverlayLease = acquireLease(pauseVisibility)

    private fun acquireLease(leases: MutableMap<Long, Long>): OverlayLease = synchronized(lock) {
        val id = ++leaseIds
        val capturedGeneration = generation
        leases[id] = capturedGeneration
        publish(mutableSnapshot.value)
        OverlayLease {
            synchronized(lock) {
                if (generation == capturedGeneration && leases.remove(id) != null) publish(mutableSnapshot.value)
            }
        }
    }

    fun reset() = synchronized(lock) {
        generation++
        hidden.clear()
        pauseVisibility.clear()
        suppressedRun = null
        mutableSnapshot.value = OverlaySnapshot()
    }

    private fun publish(state: OverlaySnapshot) {
        val automaticRun = state.runActive && state.deviceToolsRequested && suppressedRun != state.runId
        val base = automaticRun || state.completionVisible || (state.userEnabled && !state.appInForeground)
        mutableSnapshot.value = state.copy(visible = base && (hidden.isEmpty() || pauseVisibility.isNotEmpty()),
            hiddenLeaseCount = hidden.size, pauseVisibilityCount = pauseVisibility.size)
    }
}

class OverlayLease internal constructor(private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
