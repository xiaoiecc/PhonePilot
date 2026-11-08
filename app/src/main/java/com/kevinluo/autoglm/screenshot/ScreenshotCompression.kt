package com.kevinluo.autoglm.screenshot

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

internal object ScreenshotCompression {
    fun encode(bitmap: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val encoded = ByteArrayOutputStream().use { output ->
            if (bitmap.compress(format, 100, output)) output.toByteArray() else null
        }
        return checkNotNull(encoded?.takeIf { it.isNotEmpty() }) { "Unable to encode screenshot as WebP" }
    }

    fun extensionFor(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "jpg"
        else -> throw IllegalArgumentException("Unsupported screenshot image format")
    }
}
