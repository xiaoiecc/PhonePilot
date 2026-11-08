package com.kevinluo.autoglm.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.kevinluo.autoglm.computer.protocol.ResponsesImage
import com.kevinluo.autoglm.core.ChatImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

class ChatAttachmentStore private constructor(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "chat_attachments")

    suspend fun import(uris: List<Uri>): List<ChatImage> {
        val imported = mutableListOf<ChatImage>()
        return try {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    currentCoroutineContext().ensureActive()
                    imported += importOne(uri)
                }
                imported.toList()
            }
        } catch (error: Throwable) {
            // Also covers prompt cancellation while dispatching the IO result back to UI.
            withContext(NonCancellable + Dispatchers.IO) { delete(imported) }
            throw error
        }
    }

    private fun importOne(uri: Uri): ChatImage {
        directory.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contextResolver().openInputStream(uri) ?: throw IOException("Unable to open selected image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported selected image")
        var sample = 1
        while (max(bounds.outWidth / (sample * 2), bounds.outHeight / (sample * 2)) > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = contextResolver().openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unable to decode selected image")
        try {
            // WebP output drops source metadata, so apply orientation to the actual pixels.
            val orientation = readOrientation(uri)
            val transform = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
                else -> null
            }
            if (transform != null) {
                val matrix = Matrix().apply { setValues(transform) }
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) bitmap.recycle()
                bitmap = oriented
            }
            val largest = max(bitmap.width, bitmap.height)
            if (largest > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / largest
                val scaled = Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            val file = File(directory, "${UUID.randomUUID()}.webp")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            try {
                FileOutputStream(file).use { output ->
                    if (!bitmap.compress(format, QUALITY, output)) throw IOException("Unable to compress selected image")
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
            return ChatImage(file.absolutePath, "image/webp")
        } finally {
            bitmap.recycle()
        }
    }

    private fun readOrientation(uri: Uri): Int = try {
        contextResolver().openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        // Missing/unsupported EXIF must not reject an otherwise decodable image.
        ExifInterface.ORIENTATION_NORMAL
    } catch (_: IllegalArgumentException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    fun read(image: ChatImage): ResponsesImage {
        val file = ownedFile(image)
        val bytes = file.readBytes()
        return ResponsesImage("image/webp", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun delete(images: List<ChatImage>) {
        images.forEach { image -> runCatching { ownedFile(image).delete() } }
    }

    private fun ownedFile(image: ChatImage): File {
        require(image.mimeType == "image/webp") { "Unsupported attachment type" }
        val file = File(image.path).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.extension == "webp" && file.isFile) {
            "Attachment is unavailable"
        }
        return file
    }

    private fun contextResolver() = context.contentResolver

    private val context: Context = context.applicationContext

    companion object {
        private const val MAX_DIMENSION = 2048
        private const val QUALITY = 88
        @Volatile private var instance: ChatAttachmentStore? = null
        fun getInstance(context: Context): ChatAttachmentStore = instance ?: synchronized(this) {
            instance ?: ChatAttachmentStore(context.applicationContext).also { instance = it }
        }
    }
}
