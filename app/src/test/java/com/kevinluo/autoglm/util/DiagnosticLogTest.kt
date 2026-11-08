package com.kevinluo.autoglm.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticLogTest {
    @Test fun `structured fields preserve operational values and types`() {
        val rendered = DiagnosticLog.formatContext(arrayOf(
            "phase" to "RUNNING", "step" to 4, "paused" to true, "missing" to null,
            "reasons" to listOf("USER", "DRAFT"), "values" to intArrayOf(10, 20),
            "opaque" to Any(),
        ))
        assertTrue("phase=RUNNING" in rendered)
        assertTrue("step=4" in rendered)
        assertTrue("paused=true" in rendered)
        assertTrue("missing=null" in rendered)
        assertTrue("reasons=[USER,DRAFT]" in rendered)
        assertTrue("values=[10,20]" in rendered)
        assertTrue("opaque=java.lang.Object" in rendered)
    }

    @Test fun `diagnostic values redact credentials and encoded images`() {
        val rendered = DiagnosticLog.formatContext(arrayOf(
            "authorization" to "Bearer fixture-secret",
            "key" to "api_key=fixture-key",
            "image" to "data:image/webp;base64,QUJDREVGRw==",
            "url" to "https://user:password@example.com/path",
        ))
        assertFalse("fixture-secret" in rendered)
        assertFalse("fixture-key" in rendered)
        assertFalse("QUJDREVGRw" in rendered)
        assertFalse("password@example" in rendered)
        assertTrue("[redacted]" in rendered)
        assertTrue("[encoded-data]" in rendered)
    }

    @Test fun `diagnostic values are single line bounded and keys are normalized`() {
        val rendered = DiagnosticLog.formatContext(arrayOf("bad key\nname" to "x".repeat(5000) + "\nnext"))
        assertTrue(rendered.startsWith("bad_key_name="))
        assertEquals(0, rendered.count { it == '\n' })
        assertTrue(rendered.endsWith("…"))
        assertTrue(rendered.length < 4100)
    }
}
