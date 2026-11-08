package com.kevinluo.autoglm.screenshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScreenshotCompressionTest {
    @Test fun `stored filename reflects encoded WebP and readable legacy images`() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1)
        val webp = "RIFF0000WEBPpayload".toByteArray(Charsets.US_ASCII)
        val jpeg = byteArrayOf(-1, -40, -1, 3)
        assertEquals("png", ScreenshotCompression.extensionFor(png))
        assertEquals("webp", ScreenshotCompression.extensionFor(webp))
        assertEquals("jpg", ScreenshotCompression.extensionFor(jpeg))
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCompression.extensionFor(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCompression.extensionFor("RIFF".toByteArray()) }
    }
}
