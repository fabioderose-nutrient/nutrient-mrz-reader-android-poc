package io.nutrient.poc.mrz.redact

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import com.pspdfkit.annotations.RedactionAnnotation
import com.pspdfkit.document.PdfDocumentLoader
import com.pspdfkit.document.processor.NewPage
import com.pspdfkit.document.processor.PageImage
import com.pspdfkit.document.processor.PagePosition
import com.pspdfkit.document.processor.PdfProcessor
import com.pspdfkit.document.processor.PdfProcessorTask
import com.pspdfkit.utils.Size
import kotlinx.coroutines.runBlocking
import io.nutrient.poc.mrz.ocr.ImagePdf
import java.io.File

data class BurnResult(
    /** The page with the redaction annotations, not applied yet (reviewable in the Nutrient viewer). */
    val annotatedPdf: File,
    /** The page after Nutrient applied the redactions: the covered content is removed. */
    val redactedPdf: File,
    val redactedImage: Bitmap,
    val annotateMs: Long,
    val burnMs: Long,
)

/**
 * Redaction with the Nutrient Android SDK:
 *  1. the upright image becomes a one-page PDF (1 pixel = 1 point, Android PDF writer: no Nutrient
 *     Document Editor component needed);
 *  2. every region becomes a RedactionAnnotation (default black fill, field name as overlay text);
 *  3. PdfProcessorTask.applyRedactions() burns them: the image pixels under each rect are replaced,
 *     nothing of the original content survives in the output;
 *  4. the redacted page is rendered back to an image for display and PNG export.
 */
class RedactionBurner(private val context: Context) {

    fun burn(upright: Bitmap, regions: List<RedactionRegion>): BurnResult {
        val dir = File(context.cacheDir, "redaction").apply { deleteRecursively(); mkdirs() }
        var t = SystemClock.elapsedRealtime()

        val h = upright.height.toFloat()
        val page = File(dir, "page.pdf")
        ImagePdf.write(upright, page)

        val doc = PdfDocumentLoader.openDocument(context, Uri.fromFile(page))
        for (region in regions) {
            // PDF coordinates: origin bottom-left, so top > bottom.
            val rects = region.rects.map { RectF(it.left, h - it.top, it.right, h - it.bottom) }
            val annotation = RedactionAnnotation(0, rects).apply {
                outlineColor = Color.RED
                overlayText = region.field.label
                setRepeatOverlayText(false)
                contents = "${region.field.label} (${region.source.label})"
                creator = "MRZ Reader PoC"
            }
            runBlocking { doc.annotationProvider.addAnnotationToPage(annotation) }
        }
        val annotated = File(dir, "annotated.pdf")
        doc.save(annotated.path)
        val annotateMs = SystemClock.elapsedRealtime() - t

        t = SystemClock.elapsedRealtime()
        val redacted = File(dir, "redacted.pdf")
        PdfProcessor.processDocument(PdfProcessorTask.fromDocument(doc).applyRedactions(), redacted)
        val out = PdfDocumentLoader.openDocument(context, Uri.fromFile(redacted))
        val bitmap = out.renderPageToBitmap(context, 0, upright.width, upright.height)
        return BurnResult(annotated, redacted, bitmap, annotateMs, SystemClock.elapsedRealtime() - t)
    }
}
