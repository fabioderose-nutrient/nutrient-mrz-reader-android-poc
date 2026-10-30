package io.nutrient.poc.mrz.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import io.nutrient.poc.mrz.mrz.MrzData
import io.nutrient.poc.mrz.mrz.MrzFormat
import io.nutrient.poc.mrz.mrz.MrzParser
import io.nutrient.poc.mrz.mrz.MrzTextExtractor
import io.nutrient.poc.mrz.mrz.OcrLanguages
import io.nutrient.poc.mrz.ocr.ImagePrep
import io.nutrient.poc.mrz.ocr.OcrClient
import io.nutrient.poc.mrz.ocr.OcrEngineCrashException
import io.nutrient.poc.mrz.ocr.OcrOutput
import io.nutrient.poc.mrz.redact.Box
import io.nutrient.poc.mrz.redact.MrzFrame
import io.nutrient.poc.mrz.redact.PageWord
import io.nutrient.poc.mrz.redact.RedactionContext
import kotlin.math.cos

data class Timings(
    val decodeMs: Long = 0,
    val prepMs: Long = 0,
    val ipcMs: Long = 0,
    val pdfMs: Long = 0,
    val ocrMs: Long = 0,
    val textMs: Long = 0,
    val parseMs: Long = 0,
) {
    val totalMs: Long get() = decodeMs + prepMs + ipcMs + pdfMs + ocrMs + textMs + parseMs
    operator fun plus(o: Timings) = Timings(
        decodeMs + o.decodeMs, prepMs + o.prepMs, ipcMs + o.ipcMs, pdfMs + o.pdfMs,
        ocrMs + o.ocrMs, textMs + o.textMs, parseMs + o.parseMs,
    )
}

data class Attempt(
    val strategy: String,
    val durationMs: Long,
    val mrzFound: Boolean,
    val validCheckDigits: Int,
    val totalCheckDigits: Int,
    val error: String? = null,
)

data class MrzReadResult(
    /** The document upright: the input image turned by [rotation] degrees when the MRZ was found rotated. */
    val source: Bitmap,
    val mrz: MrzData?,
    /** MRZ box in [source] pixels, when the OCR returned text positions. */
    val mrzBox: RectF?,
    val portrait: Bitmap?,
    /** The image actually given to the OCR for the winning attempt. */
    val ocrInput: Bitmap?,
    val ocrText: String,
    val attempts: List<Attempt>,
    val timings: Timings,
    val rotation: Int = 0,
) {
    val status: ReadStatus
        get() = when {
            mrz == null -> ReadStatus.NOT_FOUND
            mrz.allCheckDigitsValid -> ReadStatus.VALID
            else -> ReadStatus.INVALID_CHECK_DIGITS
        }
}

enum class ReadStatus { VALID, INVALID_CHECK_DIGITS, NOT_FOUND }

/**
 * Image -> MRZ with the Nutrient OCR and the ICAO rule engine.
 *
 * Several preprocessing strategies are tried in order and the pipeline stops at the first one whose
 * MRZ passes every check digit (unless exhaustive). Each attempt is timed, so the result screen shows
 * exactly where the time goes.
 */
/** Page words, MRZ and frame for redaction, with [result] turned upright when needed. */
data class PageAnalysis(val result: MrzReadResult, val words: List<PageWord>, val mrz: MrzData?, val frame: MrzFrame?, val ms: Long)

class MrzPipeline(context: Context) {
    private companion object { const val TAG = "MrzPipeline" }

    private val ocr = OcrClient(context)

    /**
     * How a strategy transformed the input: [base] is the input turned by [rotation] degrees,
     * [bitmap] = bottom crop of [base] starting at [offsetY], resized by [scale].
     */
    private class Prepared(val bitmap: Bitmap, val base: Bitmap, val offsetY: Float, val scale: Float, val rotation: Int, val margin: Float = 0f)

    private class Strategy(val name: String, val prepare: (Bitmap) -> Prepared)

    // Keep contrast <= 1.4: near black/white input (binarized, or contrast 1.8) reliably crashes the
    // Nutrient OCR natively (SIGSEGV in libpspdf_tesseract_bridge, SDK 11.7.0).
    private val fullOriginal = Strategy("Full image, original colors") { src -> crop(src, 1f, contrast = null) }
    private val fullGray = Strategy("Full image, grayscale") { src -> crop(src, 1f, contrast = 1.3f) }
    private val bottomBand = Strategy("Bottom 35% band, grayscale") { src -> crop(src, 0.35f, contrast = 1.3f) }
    // A passport photographed upside down or in portrait: the MRZ is found once the image is turned.
    private val turned = listOf(180, 90, 270).map { deg ->
        Strategy("Image turned $deg°") { src -> crop(src, 1f, contrast = null, rotation = deg) }
    }

    /**
     * Trial mode stamps a red "For Evaluation Purposes Only" banner across the middle of every OCR'd
     * page, right where the MRZ sits on a cropped band. Without a license key the full page is read
     * first; with a license key the faster band crop goes first.
     */
    var trialMode: Boolean = false

    fun warmUp() = ocr.warmUp()

    /**
     * Full read. [decodeMs] is the time already spent decoding the image, reported in the timings.
     * [fast] limits the work to one strategy (used on live camera frames).
     */
    fun read(source: Bitmap, decodeMs: Long = 0, exhaustive: Boolean = false, fast: Boolean = false): MrzReadResult {
        val isStrip = source.width.toFloat() / source.height >= 2.5f
        val strategies = when {
            isStrip -> listOf(fullOriginal, fullGray)
            fast -> listOf(if (trialMode) fullOriginal else bottomBand)
            trialMode -> listOf(fullOriginal, fullGray, bottomBand) + turned
            else -> listOf(bottomBand, fullOriginal, fullGray) + turned
        }

        var timings = Timings(decodeMs = decodeMs)
        val attempts = mutableListOf<Attempt>()
        var best: Winner? = null
        var firstText = ""

        for (s in strategies) {
            val start = SystemClock.elapsedRealtime()
            var prepared = s.prepare(source)
            val prepMs = SystemClock.elapsedRealtime() - start
            Log.d(TAG, "${s.name}: OCR on ${prepared.bitmap.width}x${prepared.bitmap.height}")
            // The native crash depends on the exact pixels: the same image inside a white margin usually
            // passes. The retry shows in the attempt name, with its time.
            var name = s.name
            val out = try {
                ocr.recognize(prepared.bitmap)
            } catch (e: OcrEngineCrashException) {
                try {
                    prepared = padded(prepared)
                    name = "${s.name} (retried after an OCR crash)"
                    ocr.recognize(prepared.bitmap)
                } catch (e: OcrEngineCrashException) {
                    timings += Timings(prepMs = prepMs)
                    attempts += Attempt(s.name, SystemClock.elapsedRealtime() - start, false, 0, 0, e.message)
                    continue
                }
            }

            val parseStart = SystemClock.elapsedRealtime()
            val parsed = MrzTextExtractor.best(out.text)?.let { (data, candidate) -> data to candidate.ocrLines }
            val parseMs = SystemClock.elapsedRealtime() - parseStart

            timings += Timings(
                prepMs = prepMs, ipcMs = out.ipcMs, pdfMs = out.pdfBuildMs, ocrMs = out.ocrMs,
                textMs = out.textMs, parseMs = parseMs,
            )
            attempts += Attempt(
                name, SystemClock.elapsedRealtime() - start, parsed != null,
                parsed?.first?.validCheckDigitCount ?: 0, parsed?.first?.checkDigits?.size ?: 0,
            )
            if (firstText.isEmpty()) firstText = out.text

            if (parsed != null && (best == null || parsed.first.validCheckDigitCount > best.mrz.validCheckDigitCount)) {
                best = Winner(parsed.first, parsed.second, prepared, out)
            }
            if (!exhaustive && best?.mrz?.allCheckDigitsValid == true) break
        }

        val upright = best?.prepared?.base ?: source
        val box = best?.let { w ->
            val rects = w.ocrLines.mapNotNull { w.ocr.lines.getOrNull(it)?.box }
            if (rects.isEmpty()) null else toBase(unionOf(rects), w.prepared)
        }
        val portrait = if (best?.mrz?.format == MrzFormat.TD3 && box != null) PortraitCropper.crop(upright, box) else null

        return MrzReadResult(
            upright, best?.mrz, box, portrait, best?.prepared?.bitmap,
            best?.ocr?.text ?: firstText, attempts, timings, best?.prepared?.rotation ?: 0,
        )
    }

    private class Winner(val mrz: MrzData, val ocrLines: List<Int>, val prepared: Prepared, val ocr: OcrOutput)

    /** Language of the last page OCR (from the issuing state of the MRZ). */
    var lastPageLanguage: String = OcrLanguages.DEFAULT
        private set

    /** Forces the page OCR language (tests compare English with the national model). */
    var forcedPageLanguage: String? = null

    private fun pageLanguage(mrz: MrzData?) = forcedPageLanguage ?:
        OcrLanguages.forCountry(mrz?.issuingState).takeIf { it != OcrLanguages.DEFAULT } ?: OcrLanguages.forCountry(mrz?.nationality)

    /** Full-page OCR for the VIZ cross-check (printed fields above the MRZ), in the document's language. */
    fun readFullPageText(source: Bitmap, mrz: MrzData?): Pair<String, Long> {
        val start = SystemClock.elapsedRealtime()
        val prepared = crop(source, 1f, contrast = null)
        lastPageLanguage = pageLanguage(mrz)
        // Same native OCR crash as in read(): retry with a white margin, on grayscale, then with the
        // English model; only if all of them crash is the error shown.
        val attempts = listOf<() -> OcrOutput>(
            { ocr.recognize(prepared.bitmap, withBoxes = false, language = lastPageLanguage) },
            { ocr.recognize(padded(prepared).bitmap, withBoxes = false, language = lastPageLanguage) },
            { ocr.recognize(ImagePrep.grayscale(prepared.bitmap, 1.1f, -10f), withBoxes = false, language = lastPageLanguage) },
            { ocr.recognize(padded(prepared).bitmap, withBoxes = false, language = OcrLanguages.DEFAULT).also { lastPageLanguage = OcrLanguages.DEFAULT } },
        )
        var crash: OcrEngineCrashException? = null
        for (attempt in attempts) {
            try {
                return attempt().text to (SystemClock.elapsedRealtime() - start)
            } catch (e: OcrEngineCrashException) {
                crash = e
            }
        }
        throw crash!!
    }

    /**
     * Full-page OCR with every word and its boxes, in [upright] pixels (for redaction). The MRZ read
     * (English model) is the "pre-OCR": its issuing state picks the model for the printed zone.
     */
    fun readPageWords(upright: Bitmap, mrz: MrzData?): Pair<List<PageWord>, Long> {
        val start = SystemClock.elapsedRealtime()
        // Native resolution: upscaling (or shrinking the PDF page) made the Nutrient OCR miss the large
        // printed values on the specimens (OcrScaleProbeTest), at least in trial mode.
        val prepared = crop(upright, 1f, contrast = null)
        lastPageLanguage = pageLanguage(mrz)
        // Same native OCR crash as in read(): retry on grayscale, then with the English model, and if the
        // engine still crashes return no words (the MRZ-anchor method does not need them).
        val gray by lazy { ImagePrep.grayscale(prepared.bitmap, 1.1f, -10f) }
        var margin = 0f
        val attempts = listOf<() -> OcrOutput>(
            { ocr.recognize(prepared.bitmap, withBoxes = false, withWords = true, language = lastPageLanguage) },
            { padded(prepared).let { margin = it.margin; ocr.recognize(it.bitmap, withBoxes = false, withWords = true, language = lastPageLanguage) } },
            { ocr.recognize(gray, withBoxes = false, withWords = true, language = lastPageLanguage) },
            { padded(prepared).let { margin = it.margin; ocr.recognize(it.bitmap, withBoxes = false, withWords = true, language = OcrLanguages.DEFAULT) }.also { lastPageLanguage = OcrLanguages.DEFAULT } },
        )
        val out = attempts.firstNotNullOfOrNull { attempt -> try { attempt() } catch (e: OcrEngineCrashException) { margin = 0f; null } }
            ?: return emptyList<PageWord>() to (SystemClock.elapsedRealtime() - start)
        fun box(r: RectF) = Box(
            (r.left - margin) / prepared.scale, (r.top - margin) / prepared.scale,
            (r.right - margin) / prepared.scale, (r.bottom - margin) / prepared.scale,
        )
        val words = out.words.map { PageWord(it.text, it.line, box(it.box), box(it.head), box(it.tail)) }
        return words to (SystemClock.elapsedRealtime() - start)
    }

    /**
     * Page OCR + MRZ frame for redaction. The Nutrient OCR also reads upside-down text, so a read can
     * succeed on the "turned 180°" attempt of an image that was already upright (or the other way
     * round): the reading direction of the MRZ words tells, and the page is turned to match.
     */
    fun analyzePage(r: MrzReadResult): PageAnalysis {
        val (words, ms) = readPageWords(r.source, r.mrz)
        val mrzBox = r.mrzBox?.let { Box(it.left, it.top, it.right, it.bottom) }
        val (mrz, frame) = RedactionContext.resolve(words, r.mrz, mrzBox)
        if (frame == null || cos(frame.angleRad) > -0.7) return PageAnalysis(r, words, mrz, frame, ms)

        val w = r.source.width.toFloat()
        val h = r.source.height.toFloat()
        fun Box.turned() = Box(w - right, h - bottom, w - left, h - top)
        val turnedWords = words.map { it.copy(box = it.box.turned(), head = it.head.turned(), tail = it.tail.turned()) }
        val turnedBox = mrzBox?.turned()
        val upright = ImagePrep.rotate(r.source, 180)
        val (mrz2, frame2) = RedactionContext.resolve(turnedWords, mrz, turnedBox)
        val fixed = r.copy(
            source = upright,
            mrzBox = turnedBox?.let { RectF(it.left, it.top, it.right, it.bottom) },
            portrait = if (r.mrz?.format == MrzFormat.TD3 && turnedBox != null) {
                PortraitCropper.crop(upright, RectF(turnedBox.left, turnedBox.top, turnedBox.right, turnedBox.bottom))
            } else r.portrait,
            rotation = (r.rotation + 180) % 360,
        )
        Log.i(TAG, "MRZ read upside down: page turned 180°")
        return PageAnalysis(fixed, turnedWords, mrz2, frame2, ms)
    }

    /**
     * Optional bottom crop, optional grayscale + contrast. Resampling blurs the OCR-B glyphs, so the
     * width is only touched when it is outside 1000-2400 px.
     */
    private fun crop(src: Bitmap, ratio: Float, contrast: Float?, rotation: Int = 0, minWidth: Int = 1000): Prepared {
        val base = ImagePrep.rotate(src, rotation)
        val crop = if (ratio < 1f) ImagePrep.cropBottom(base, ratio) else base
        val offsetY = (base.height - crop.height).toFloat()
        val scaled = ImagePrep.scaleUpTo(ImagePrep.scaleDown(crop, 2400), minWidth)
        val scale = scaled.width.toFloat() / crop.width
        val out = if (contrast != null) ImagePrep.grayscale(scaled, contrast, brightness = -18f * contrast) else scaled
        return Prepared(out, base, offsetY, scale, rotation)
    }

    /** [p] inside a white margin of 6% of its width; boxes map back through the shifted offset. */
    private fun padded(p: Prepared): Prepared {
        val m = (p.bitmap.width * 0.06f).toInt()
        val out = Bitmap.createBitmap(p.bitmap.width + 2 * m, p.bitmap.height + 2 * m, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(p.bitmap, m.toFloat(), m.toFloat(), null) }
        return Prepared(out, p.base, p.offsetY, p.scale, p.rotation, margin = m.toFloat())
    }

    private fun toBase(r: RectF, p: Prepared) =
        RectF(
            (r.left - p.margin) / p.scale, (r.top - p.margin) / p.scale + p.offsetY,
            (r.right - p.margin) / p.scale, (r.bottom - p.margin) / p.scale + p.offsetY,
        )

    private fun unionOf(rects: List<RectF>) = RectF(rects[0]).apply { rects.drop(1).forEach { union(it) } }
}
