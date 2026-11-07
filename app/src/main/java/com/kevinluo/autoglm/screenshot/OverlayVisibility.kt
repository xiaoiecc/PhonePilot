package com.kevinluo.autoglm.screenshot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Real Android hosts hide through a policy-owned lease, not a second visibility boolean. */
interface ScopedFloatingWindowController : FloatingWindowController {
    fun acquireHidden(): AutoCloseable
    fun acquirePauseVisibility(): AutoCloseable
}

/** Hides only for capture/input; a suspended pause checkpoint must leave controls accessible. */
internal suspend fun <T> withOverlayHidden(
    window: FloatingWindowController?,
    checkpoint: suspend () -> Unit = {},
    hideDelayMs: Long = 150,
    showDelayMs: Long = 0,
    bringToFront: Boolean = false,
    uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    block: suspend () -> T,
): T = coroutineScope {
    val scoped = window as? ScopedFloatingWindowController
    suspend fun awaitVisibleCheckpoint(ready: Deferred<Unit>) {
        var visibility: AutoCloseable? = null
        try {
            if (scoped != null && !ready.isCompleted) {
                // Assign inside Main before returning: cancellation must not leak a lease.
                withContext(uiDispatcher) { visibility = scoped.acquirePauseVisibility() }
            }
            ready.await()
        } finally {
            if (visibility != null) withContext(NonCancellable + uiDispatcher) { visibility?.close() }
        }
    }
    // A nested scope may already be inside another hide before its FIRST checkpoint.
    // Probe without awaiting so that this initial boundary cannot strand the outer lease.
    val initial = async(start = CoroutineStart.UNDISPATCHED) { checkpoint() }
    awaitVisibleCheckpoint(initial)
    val wasVisible = if (window == null) false else withContext(uiDispatcher) { window.isVisible() }
    var lease: AutoCloseable? = null
    var hidden = false
    suspend fun restore() {
        if (lease != null || hidden) {
            withContext(NonCancellable + uiDispatcher) {
                delay(showDelayMs)
                val owned = lease
                lease = null
                if (owned != null) owned.close()
                else if (bringToFront) window?.showAndBringToFront() else window?.show()
            }
            hidden = false
        }
    }
    try {
        if (wasVisible || scoped != null) {
            while (true) {
                // Mark ownership before switching dispatchers: cancellation may happen on return.
                withContext(uiDispatcher) {
                    if (scoped != null) lease = scoped.acquireHidden()
                    else { hidden = true; window?.hide() }
                }
                if (wasVisible) delay(hideDelayMs)
                // PhoneAgent checkpoints complete immediately unless paused. Restore before
                // awaiting a suspended checkpoint, then re-hide and re-check before any input.
                val ready = async(start = CoroutineStart.UNDISPATCHED) { checkpoint() }
                if (ready.isCompleted) {
                    ready.await()
                    break
                }
                restore()
                awaitVisibleCheckpoint(ready)
            }
        } else {
            checkpoint()
        }
        block()
    } finally {
        restore()
    }
}
