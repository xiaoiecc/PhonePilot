package com.kevinluo.autoglm.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.app.KeyguardManager
import androidx.core.content.ContextCompat
import com.kevinluo.autoglm.core.ScreenEvent
import com.kevinluo.autoglm.task.TaskExecutionManager

/** Screen broadcasts only forward events to the existing placement coordinator. */
object ScreenModeController {
    private var receiver: BroadcastReceiver? = null

    fun initialize(context: Context) {
        if (receiver != null) return
        val application = context.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                com.kevinluo.autoglm.util.DiagnosticLog.event("Screen", "broadcast", "action" to intent.action)
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> TaskExecutionManager.onScreenEvent(ScreenEvent.SCREEN_OFF)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> refresh(context)
                }
            }
        }
        ContextCompat.registerReceiver(application, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun refresh(context: Context) {
        initialize(context)
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val event = if (!power.isInteractive || keyguard.isKeyguardLocked) ScreenEvent.SCREEN_OFF else ScreenEvent.USER_PRESENT
        val runId = TaskExecutionManager.taskState.value.runId
        val accepted = TaskExecutionManager.onScreenEvent(event)
        com.kevinluo.autoglm.util.DiagnosticLog.event("Screen", "state",
            "interactive" to power.isInteractive, "keyguardLocked" to keyguard.isKeyguardLocked,
            "event" to event, "runId" to runId, "accepted" to accepted)
    }
}
