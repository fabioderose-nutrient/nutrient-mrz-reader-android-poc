package io.nutrient.poc.mrz.ocr

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import com.pspdfkit.document.PdfDocumentLoader
import com.pspdfkit.document.processor.NewPage
import com.pspdfkit.document.processor.PageImage
import com.pspdfkit.document.processor.PagePosition
import com.pspdfkit.document.processor.PdfProcessor
import com.pspdfkit.document.processor.PdfProcessorTask
import com.pspdfkit.document.processor.ocr.OcrLanguage
import com.pspdfkit.utils.Size
import java.io.File

/** One OCR text line with its box in the pixel space of the image given to the OCR. */
data class OcrLine(val text: String, val box: RectF?)

/**
 * One OCR word (whitespace-separated). [head] and [tail] are the boxes of its first and last
 * character: on a tilted photo the vector between them gives the text direction and the pitch.
 */
data class OcrWord(val text: String, val line: Int, val box: RectF, val head: RectF, val tail: RectF)

data class OcrOutput(
    val text: String,
    val lines: List<OcrLine>,
    val pdfBuildMs: Long,
    val ocrMs: Long,
    val textMs: Long,
    /** Process hop to the isolated OCR service (image hand-off and reply). */
    val ipcMs: Long = 0,
    val words: List<OcrWord> = emptyList(),
)

/**
 * On-device OCR with the Nutrient Android SDK (`nutrient-ocr` + English trained data).
 *
 *   image file -> one-page PDF (Android PdfDocument) -> performOcrOnPages -> getPageText / getPageTextRects
 *
 * Runs inside [OcrService] (separate process). Nothing leaves the device; temporary PDFs are deleted.
 */
class NutrientOcrEngine(private val context: Context) {
    companion object {
        /** Default page scale: see the OCR scale probe in the README. */
        var POINTS_PER_PIXEL = 1f
    }

    private val workDir = File(context.cacheDir, "ocr").apply { mkdirs() }

    fun recognize(
        image: File, width: Int, height: Int, withBoxes: Boolean, withWords: Boolean = false,
        language: OcrLanguage = OcrLanguage.ENGLISH, pointsPerPixel: Float = POINTS_PER_PIXEL,
    ): OcrOutput {
        val stamp = System.nanoTime()
        val basePdf = File(workDir, "page_$stamp.pdf")
        val ocrPdf = File(workDir, "ocr_$stamp.pdf")
        try {
            var t = SystemClock.elapsedRealtime()
            // Page size in points: [pointsPerPixel] sets the physical size the OCR renders the image at.
            val k = pointsPerPixel
            ImagePdf.write(image, basePdf, k)
            val pdfBuildMs = SystemClock.elapsedRealtime() - t

            t = SystemClock.elapsedRealtime()
            val source = PdfDocumentLoader.openDocument(context, Uri.fromFile(basePdf))
            PdfProcessor.processDocument(
                PdfProcessorTask.fromDocument(source).performOcrOnPages(setOf(0), language),
                ocrPdf,
            )
            val ocrMs = SystemClock.elapsedRealtime() - t

            t = SystemClock.elapsedRealtime()
            val doc = PdfDocumentLoader.openDocument(context, Uri.fromFile(ocrPdf))
            val text = doc.getPageText(0)
            val pageHeight = doc.getPageSize(0).height
            // OCR rects are in points: back to image pixels.
            fun px(r: RectF?) = r?.let { RectF(it.left / k, it.top / k, it.right / k, it.bottom / k) }
            val lines = mutableListOf<OcrLine>()
            var start = 0
            for (line in text.split('\n')) {
                val box = if (withBoxes && line.isNotBlank()) {
                    runCatching { px(union(doc.getPageTextRects(0, start, line.length), pageHeight)) }.getOrNull()
                } else null
                lines += OcrLine(line, box)
                start += line.length + 1
            }
            val words = if (withWords) words(text) { start, length -> doc.getPageTextRects(0, start, length) }
                .mapNotNull { (w, rects) -> w.copy(box = px(union(rects.box, pageHeight)) ?: return@mapNotNull null,
                    head = px(union(rects.head, pageHeight)) ?: return@mapNotNull null,
                    tail = px(union(rects.tail, pageHeight)) ?: return@mapNotNull null) }
            else emptyList()
            return OcrOutput(text, lines, pdfBuildMs, ocrMs, SystemClock.elapsedRealtime() - t, words = words)
        } finally {
            basePdf.delete()
            ocrPdf.delete()
        }
    }

    private class WordRects(val box: List<RectF>, val head: List<RectF>, val tail: List<RectF>)

    /** Whitespace-separated words with the PDF rects of the whole word, its first and its last character. */
    private fun words(text: String, rects: (Int, Int) -> List<RectF>): List<Pair<OcrWord, WordRects>> {
        val lineOf = IntArray(text.length + 1)
        for (i in text.indices) lineOf[i + 1] = lineOf[i] + if (text[i] == '\n') 1 else 0
        val empty = RectF()
        return Regex("\\S+").findAll(text).mapNotNull { m ->
            val start = m.range.first
            val len = m.value.length
            runCatching {
                OcrWord(m.value, lineOf[start], empty, empty, empty) to
                    WordRects(rects(start, len), rects(start, 1), rects(start + len - 1, 1))
            }.getOrNull()
        }.toList()
    }

    /** PDF rects (origin bottom-left) -> one rect in top-left pixel space. */
    private fun union(rects: List<RectF>, pageHeight: Float): RectF? {
        if (rects.isEmpty()) return null
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (r in rects) {
            left = minOf(left, r.left, r.right)
            right = maxOf(right, r.left, r.right)
            low = minOf(low, r.top, r.bottom)
            high = maxOf(high, r.top, r.bottom)
        }
        return RectF(left, pageHeight - high, right, pageHeight - low)
    }
}
