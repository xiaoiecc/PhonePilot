package com.kevinluo.autoglm.screenshot

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

internal enum class ScreenshotCodec { PNG, WEBP_LOSSLESS }

/** Preserve the scaled UI pixels; do not trade text edges for a smaller lossy image. */
internal object ScreenshotCompression {
    fun encode(bitmap: Bitmap, originalPng: ByteArray? = null): ByteArray = select(Build.VERSION.SDK_INT) { codec ->
        if (codec == ScreenshotCodec.PNG && originalPng != null) return@select originalPng
        val format = when (codec) {
            ScreenshotCodec.PNG -> Bitmap.CompressFormat.PNG
            ScreenshotCodec.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else Bitmap.CompressFormat.PNG
        }
        ByteArrayOutputStream().use { output ->
            // For lossless WebP this controls compression effort, not visual quality.
            if (bitmap.compress(format, 50, output)) output.toByteArray() else null
        }
    }

    internal fun select(sdkInt: Int, encode: (ScreenshotCodec) -> ByteArray?): ByteArray {
        val codecs = if (sdkInt >= 30) listOf(ScreenshotCodec.WEBP_LOSSLESS, ScreenshotCodec.PNG)
            else listOf(ScreenshotCodec.PNG)
        val candidates = codecs.mapNotNull { codec ->
            try { encode(codec)?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
        }
        return checkNotNull(candidates.minByOrNull { it.size }) { "Unable to encode screenshot losslessly" }
    }

    fun extensionFor(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "jpg"
        else -> throw IllegalArgumentException("Unsupported screenshot image format")
    }
}
