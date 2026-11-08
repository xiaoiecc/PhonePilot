package com.kevinluo.autoglm.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

internal object ChatImageDecoder {
    fun decode(path: String, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / (sample * 2), bounds.outHeight / (sample * 2)) > targetSize) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
