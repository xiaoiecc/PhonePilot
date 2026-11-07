package com.kevinluo.autoglm.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TokenCountFormatterTest {
    @Test fun `unknown differs from metered zero`() {
        assertEquals("—", formatTokenCount(null))
        assertEquals("0", formatTokenCount(0))
        assertEquals("845", formatTokenCount(845))
    }

    @Test fun `decimal units use a single decimal and promote across rounding boundaries`() {
        assertEquals("1.0K", formatTokenCount(1000))
        assertEquals("12.3K", formatTokenCount(12345))
        assertEquals("999.9K", formatTokenCount(999949))
        assertEquals("1.0M", formatTokenCount(999950))
        assertEquals("1.2M", formatTokenCount(1234567))
        assertEquals("2.0B", formatTokenCount(2000000000))
        assertEquals("9.2E", formatTokenCount(Long.MAX_VALUE))
    }
}
