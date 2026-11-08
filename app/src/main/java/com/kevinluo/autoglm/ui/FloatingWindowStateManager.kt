package com.kevinluo.autoglm.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kevinluo.autoglm.core.OverlayPolicy
import com.kevinluo.autoglm.core.OverlaySnapshot
import com.kevinluo.autoglm.core.RunSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Android host adapter. Visibility/minimization have exactly one writer: the pure policy. */
object FloatingWindowStateManager {
    val policy = OverlayPolicy()
    val snapshot: StateFlow<OverlaySnapshot> get() = policy.snapshot
    private var context: Context? = null
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    fun initialize(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        scope.launch { policy.snapshot.collect { reconcile(it) } }
    }

    fun enableByUser(context: Context) { initialize(context); ensureServiceReady(); policy.enableUser() }
    fun disableByUser() { policy.disableUser() }
    fun toggleByUser(context: Context) {
        initialize(context)
        if (!policy.snapshot.value.userEnabled && !policy.snapshot.value.runActive) ensureServiceReady()
        policy.toggleUser()
    }
    fun onAppForeground() { policy.onAppForeground() }
    fun onAppBackground(context: Context) { initialize(context); policy.onAppBackground() }
    fun onRunChanged(run: RunSnapshot) { policy.onRunChanged(run) }
    fun setMinimized(minimized: Boolean) { policy.setMinimized(minimized) }
    fun close(context: Context) {
        initialize(context)
        policy.close()
        // Closing the surface does not remove a running task's foreground-service lifetime.
        if (!policy.snapshot.value.runActive) FloatingWindowService.getInstance()?.stopSelf()
    }

    /** Acquire on Main so the physical host is hidden before capture/input can start. */
    fun acquireHide(context: Context): AutoCloseable = acquireLease(context, "hide", policy::acquireHide)
    fun acquirePauseVisibility(context: Context): AutoCloseable = acquireLease(context, "pauseVisibility", policy::acquirePauseVisibility)

    private fun acquireLease(context: Context, kind: String, acquire: () -> AutoCloseable): AutoCloseable {
        initialize(context)
        val lease = acquire()
        com.kevinluo.autoglm.util.DiagnosticLog.event("OverlayLease", "acquired", "kind" to kind,
            "hiddenLeases" to snapshot.value.hiddenLeaseCount, "pauseLeases" to snapshot.value.pauseVisibilityCount)
        reconcile(policy.snapshot.value)
        return AutoCloseable {
            com.kevinluo.autoglm.util.DiagnosticLog.event("OverlayLease", "releasing", "kind" to kind,
                "hiddenLeases" to snapshot.value.hiddenLeaseCount, "pauseLeases" to snapshot.value.pauseVisibilityCount)
            lease.close()
            reconcile(policy.snapshot.value)
            com.kevinluo.autoglm.util.DiagnosticLog.event("OverlayLease", "released", "kind" to kind,
                "hiddenLeases" to snapshot.value.hiddenLeaseCount, "pauseLeases" to snapshot.value.pauseVisibilityCount)
        }
    }

    fun shouldBeVisible(): Boolean = snapshot.value.visible
    fun isEnabled(): Boolean = snapshot.value.let { it.userEnabled || it.runActive || it.completionVisible }
    fun isUserEnabled(): Boolean = snapshot.value.userEnabled
    fun reset() { policy.reset() }

    private fun reconcile(state: OverlaySnapshot) {
        val application = context ?: return
        val service = FloatingWindowService.getInstance()
        com.kevinluo.autoglm.util.DiagnosticLog.event("Overlay", "reconcile",
            "userEnabled" to state.userEnabled, "appInForeground" to state.appInForeground,
            "visible" to state.visible, "minimized" to state.minimized,
            "runId" to state.runId, "runActive" to state.runActive,
            "deviceToolsRequested" to state.deviceToolsRequested,
            "completionVisible" to state.completionVisible,
            "hiddenLeases" to state.hiddenLeaseCount, "pauseLeases" to state.pauseVisibilityCount,
            "servicePresent" to (service != null), "windowAttached" to (service?.isVisible() == true))
        if (state.runActive) ensureServiceReady()
        if (state.visible) {
            if (service == null) ContextCompat.startForegroundService(application, Intent(application, FloatingWindowService::class.java))
            else service.show()
        } else service?.hide()
        FloatingWindowService.getInstance()?.applyMinimizedPresentation(policy.snapshot.value.minimized)
    }

    private fun ensureServiceReady() {
        val application = context ?: return
        if (FloatingWindowService.getInstance() == null) {
            ContextCompat.startForegroundService(application, Intent(application, FloatingWindowService::class.java))
        }
    }
}
