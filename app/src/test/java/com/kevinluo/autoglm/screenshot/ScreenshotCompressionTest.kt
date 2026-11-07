package com.kevinluo.autoglm.screenshot

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScreenshotCompressionTest {
    @Test fun `chooses the smallest successful lossless candidate without modifying bytes`() {
        val webp = ByteArray(80) { 7 }
        val png = ByteArray(120) { 3 }
        val selected = ScreenshotCompression.select(30) { if (it == ScreenshotCodec.PNG) png else webp }
        assertArrayEquals(webp, selected)
        val smallerPng = ByteArray(20) { 4 }
        assertArrayEquals(smallerPng, ScreenshotCompression.select(35) {
            if (it == ScreenshotCodec.PNG) smallerPng else webp
        })
    }

    @Test fun `pre Android 11 never attempts the unavailable lossless WebP API`() {
        val attempted = mutableListOf<ScreenshotCodec>()
        val png = byteArrayOf(1, 2, 3)
        assertArrayEquals(png, ScreenshotCompression.select(24) { attempted += it; png })
        assertEquals(listOf(ScreenshotCodec.PNG), attempted)
    }

    @Test fun `failed or empty encoder output falls back to the other lossless codec`() {
        val png = byteArrayOf(3, 4)
        assertArrayEquals(png, ScreenshotCompression.select(30) {
            if (it == ScreenshotCodec.WEBP_LOSSLESS) null else png
        })
        assertArrayEquals(png, ScreenshotCompression.select(30) {
            if (it == ScreenshotCodec.WEBP_LOSSLESS) byteArrayOf() else png
        })
        assertArrayEquals(png, ScreenshotCompression.select(30) {
            if (it == ScreenshotCodec.WEBP_LOSSLESS) throw IllegalStateException("Encoder rejected image") else png
        })
    }

    @Test fun `total encode failure does not upload an empty image`() {
        assertThrows(IllegalStateException::class.java) { ScreenshotCompression.select(30) { null } }
    }

    @Test fun `stored filename reflects encoded bytes including PNG fallback and existing images`() {
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
