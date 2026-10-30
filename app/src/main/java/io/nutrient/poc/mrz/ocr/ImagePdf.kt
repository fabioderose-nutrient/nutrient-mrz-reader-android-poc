package io.nutrient.poc.mrz.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Wraps an image into a one-page PDF with the Android framework PDF writer, so the Nutrient license
 * needs no Document Editor component (Nutrient's PdfProcessor.newPage requires it). The page is
 * [pointsPerPixel] points per image pixel; 1 = one point per pixel, so PDF coordinates are pixels.
 */
object ImagePdf {
    fun write(image: Bitmap, out: File, pointsPerPixel: Float = 1f) {
        val w = (image.width * pointsPerPixel).toInt().coerceAtLeast(1)
        val h = (image.height * pointsPerPixel).toInt().coerceAtLeast(1)
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            page.canvas.drawBitmap(image, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    fun write(imageFile: File, out: File, pointsPerPixel: Float = 1f) {
        val bmp = BitmapFactory.decodeFile(imageFile.path) ?: error("Unreadable image ${imageFile.name}")
        write(bmp, out, pointsPerPixel)
    }
}
