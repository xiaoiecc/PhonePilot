package com.kevinluo.autoglm.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityPermission {
    fun isServiceReady(context: Context): Boolean {
        val expected = ComponentName(context, PhoneAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
        return enabled && PhoneAccessibilityService.instance != null
    }
}
