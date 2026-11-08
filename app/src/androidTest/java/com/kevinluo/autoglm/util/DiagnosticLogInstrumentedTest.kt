package com.kevinluo.autoglm.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinluo.autoglm.settings.SettingsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real logging/storage only: no Activity, model request or device operation. */
@RunWith(AndroidJUnit4::class)
class DiagnosticLogInstrumentedTest {
    @Test fun detailedModeWritesStructuredEventAndRestoresPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager.getInstance(context)
        val previous = settings.isDetailedDiagnosticsEnabled()
        LogFileManager.init(context)
        try {
            settings.setDetailedDiagnosticsEnabled(true)
            DiagnosticLog.setDetailedEnabled(true)
            assertTrue(settings.isDetailedDiagnosticsEnabled())
            assertTrue(DiagnosticLog.isDetailedEnabled)
            val marker = "instrumented_${System.nanoTime()}"
            DiagnosticLog.event("SelfTest", marker, "answer" to 42, "enabled" to true)
            val content = LogFileManager.getLogFiles().first().readText()
            assertTrue(content.contains("AutoGLM/Diag/SelfTest"))
            assertTrue(content.contains(marker))
            assertTrue(content.contains("answer=42"))
            assertTrue(content.contains("thread="))
        } finally {
            DiagnosticLog.setDetailedEnabled(previous)
            settings.setDetailedDiagnosticsEnabled(previous)
            assertFalse(DiagnosticLog.isDetailedEnabled != previous)
        }
    }
}
