package com.kevinluo.autoglm.history

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatAttachmentStoreTest {
    @Test
    fun jpegOrientationIsAppliedToPixelsForEveryExifTransform() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ChatAttachmentStore.getInstance(context)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expectedCorners = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) {
            val source = File.createTempFile("attachment-orientation-", ".jpg", context.cacheDir)
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    bitmap.setPixel(x, y, colors[(if (y < 20) 0 else 2) + if (x < 40) 0 else 1])
                }
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                ExifInterface(source.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val imported = store.import(listOf(Uri.fromFile(source)))
                try {
                    val result = requireNotNull(BitmapFactory.decodeFile(imported.single().path))
                    try {
                        assertEquals(if (orientation >= 5) 40 else 80, result.width)
                        assertEquals(if (orientation >= 5) 80 else 40, result.height)
                        val corners = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) ->
                            val pixel = result.getPixel(result.width * x / 4, result.height * y / 4)
                            colors.indices.minBy { index ->
                                val target = colors[index]
                                kotlin.math.abs(Color.red(pixel) - Color.red(target)) +
                                    kotlin.math.abs(Color.green(pixel) - Color.green(target)) +
                                    kotlin.math.abs(Color.blue(pixel) - Color.blue(target))
                            }
                        }
                        assertEquals("EXIF orientation $orientation", expectedCorners[orientation - 1], corners)
                    } finally {
                        result.recycle()
                    }
                } finally {
                    store.delete(imported)
                }
            } finally {
                bitmap.recycle()
                source.delete()
            }
        }
    }

    @Test
    fun multipleImagesAreImportedCompressedAndReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val store = ChatAttachmentStore.getInstance(context)
        val inputs = listOf("autoglm-first.jpg", "autoglm-second.jpg").mapIndexed { index, name ->
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }))
            val bitmap = Bitmap.createBitmap(3000 + index, 1200, Bitmap.Config.ARGB_8888)
            try {
                requireNotNull(resolver.openOutputStream(uri)).use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                }
            } finally {
                bitmap.recycle()
            }
            uri
        }
        val imported = mutableListOf<com.kevinluo.autoglm.core.ChatImage>()
        try {
            imported += store.import(inputs)
            assertEquals(2, imported.size)
            imported.forEach { image ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.path, bounds)
                assertEquals(2048, bounds.outWidth)
                assertTrue(bounds.outHeight > 0)
                val bytes = Base64.decode(store.read(image).base64Data, Base64.DEFAULT)
                assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            }
        } finally {
            store.delete(imported)
            inputs.forEach { resolver.delete(it, null, null) }
        }
    }
}
