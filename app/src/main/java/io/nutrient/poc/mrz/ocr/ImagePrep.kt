package io.nutrient.poc.mrz.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** Bitmap helpers used before OCR. Pure Android APIs, no image library. */
object ImagePrep {

    /** Decodes [uri], applies the EXIF orientation and caps the longest side at [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "Unreadable image: $uri" }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Unreadable image: $uri")

        val orientation = runCatching {
            resolver.openInputStream(uri).use { ExifInterface(it!!).rotationDegrees }
        }.getOrDefault(0)
        return scaleDown(rotate(decoded, orientation), maxSide)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    }

    /** Scales so the width is at least [minWidth] (the OCR likes glyphs ~30 px wide). */
    fun scaleUpTo(src: Bitmap, minWidth: Int): Bitmap {
        if (src.width >= minWidth) return src
        val f = minWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, minWidth, (src.height * f).toInt(), true)
    }

    fun cropBottom(src: Bitmap, ratio: Float): Bitmap {
        val h = (src.height * ratio).toInt().coerceIn(1, src.height)
        return Bitmap.createBitmap(src, 0, src.height - h, src.width, h)
    }

    fun grayscale(src: Bitmap, contrast: Float = 1.4f, brightness: Float = -25f): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val boost = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        gray.postConcat(boost)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
        return out
    }
}
