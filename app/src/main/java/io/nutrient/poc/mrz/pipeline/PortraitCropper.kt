package io.nutrient.poc.mrz.pipeline

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Cuts the holder's portrait out of a TD3 data page using only the MRZ geometry.
 *
 * ICAO 9303 fixes the OCR-B pitch (2.54 mm per character), so the MRZ width gives the page scale.
 * The portrait sits on the left, above the MRZ. Offsets below are in millimetres from the
 * top-left corner of the MRZ and were set for TD3 passports; rotation and strong perspective
 * are not corrected in this PoC.
 */
object PortraitCropper {
    private const val MRZ_WIDTH_MM = 44 * 2.54f
    private const val LEFT_MM = -3f
    private const val RIGHT_MM = 40f
    private const val TOP_MM = -56f
    private const val BOTTOM_MM = -5f

    fun crop(source: Bitmap, mrzBox: RectF): Bitmap? {
        val pxPerMm = mrzBox.width() / MRZ_WIDTH_MM
        if (pxPerMm <= 0f) return null
        val left = (mrzBox.left + LEFT_MM * pxPerMm).toInt().coerceIn(0, source.width)
        val right = (mrzBox.left + RIGHT_MM * pxPerMm).toInt().coerceIn(0, source.width)
        val top = (mrzBox.top + TOP_MM * pxPerMm).toInt().coerceIn(0, source.height)
        val bottom = (mrzBox.top + BOTTOM_MM * pxPerMm).toInt().coerceIn(0, source.height)
        if (right - left < 40 || bottom - top < 40) return null
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }
}
