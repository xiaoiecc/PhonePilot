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
        FloatingWindowService.getInstance()?.stopSelf()
    }

    /** Acquire on Main so the physical host is hidden before capture/input can start. */
    fun acquireHide(context: Context): AutoCloseable = acquireLease(context, policy::acquireHide)
    fun acquirePauseVisibility(context: Context): AutoCloseable = acquireLease(context, policy::acquirePauseVisibility)

    private fun acquireLease(context: Context, acquire: () -> AutoCloseable): AutoCloseable {
        initialize(context)
        val lease = acquire()
        reconcile(policy.snapshot.value)
        return AutoCloseable {
            lease.close()
            reconcile(policy.snapshot.value)
        }
    }

    fun shouldBeVisible(): Boolean = snapshot.value.visible
    fun isEnabled(): Boolean = snapshot.value.let { it.userEnabled || it.runActive || it.completionVisible }
    fun isUserEnabled(): Boolean = snapshot.value.userEnabled
    fun reset() { policy.reset() }

    private fun reconcile(state: OverlaySnapshot) {
        val application = context ?: return
        val service = FloatingWindowService.getInstance()
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
