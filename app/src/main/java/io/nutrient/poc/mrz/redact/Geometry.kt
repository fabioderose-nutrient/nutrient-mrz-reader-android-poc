package io.nutrient.poc.mrz.redact

import io.nutrient.poc.mrz.mrz.MrzFormat
import io.nutrient.poc.mrz.mrz.VizCrossCheck
import io.nutrient.poc.mrz.mrz.VizText
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Axis-aligned box in image pixels (top-left origin). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2

    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    fun inflate(d: Float) = Box(left - d, top - d, right + d, bottom + d)
    fun clip(w: Float, h: Float) = Box(left.coerceIn(0f, w), top.coerceIn(0f, h), right.coerceIn(0f, w), bottom.coerceIn(0f, h))
    val isEmpty get() = width <= 1f || height <= 1f

    companion object {
        fun around(points: List<Pt>) = Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        fun unionOf(boxes: List<Box>) = boxes.reduce { a, b -> a.union(b) }
    }
}

data class Pt(val x: Float, val y: Float)

/** One OCR word of the page, with the boxes of its first and last 3 characters. */
data class PageWord(val text: String, val line: Int, val box: Box, val head: Box, val tail: Box) {
    val norm: String = VizText.normalize(text)

    private val mrzChars get() = text.count { it.isUpperCase() || it.isDigit() || it in "<«‹" }

    /**
     * MRZ lines come out of the OCR as runs of A-Z, 0-9 and filler look-alikes, sometimes cut into
     * pieces ("IDD<<T220001" + "293<<<<…"): long runs, or shorter ones with fillers.
     */
    val isMrzToken: Boolean
        get() = mrzChars >= text.length * 0.9 && (
            (text.length >= 15 && text.count { it in "<«‹" || it == 'K' } >= 2) ||
                (text.length >= 8 && text.count { it in "<«‹" } >= 2)
            )

    /** A short piece that can belong to an MRZ line ("<<<6"), once the line itself is known. */
    val isMrzPiece: Boolean get() = text.length >= 2 && mrzChars >= text.length * 0.8
}

/**
 * Coordinate frame attached to the MRZ, in millimetres: origin at the top-left corner of the first
 * MRZ character, x along the text direction, y towards the bottom of the document. ICAO 9303 fixes
 * the OCR-B pitch at 2.54 mm, so the MRZ gives position, scale and rotation of the whole data page,
 * whatever the distance and tilt of the photo.
 */
data class MrzFrame(
    val origin: Pt,
    val angleRad: Double,
    val pxPerMm: Float,
    val format: MrzFormat,
    /** Measured distance between two MRZ lines (centre to centre). */
    val lineSpacingMm: Float = LINE_PITCH_MM,
) {
    private val c = cos(angleRad).toFloat()
    private val s = sin(angleRad).toFloat()

    fun toImage(xMm: Float, yMm: Float): Pt {
        val x = xMm * pxPerMm
        val y = yMm * pxPerMm
        return Pt(origin.x + x * c - y * s, origin.y + x * s + y * c)
    }

    /** Corners of a rectangle given in frame millimetres, in image pixels (a rotated quad). */
    fun quad(x0: Float, y0: Float, x1: Float, y1: Float) =
        listOf(toImage(x0, y0), toImage(x1, y0), toImage(x1, y1), toImage(x0, y1))

    val angleDeg: Double get() = Math.toDegrees(angleRad)

    companion object {
        const val PITCH_MM = 2.54f
        /** OCR-B line pitch in an MRZ (centre to centre), approximately. */
        const val LINE_PITCH_MM = 4.4f
        const val CHAR_HEIGHT_MM = 2.4f
        /** Height of the OCR box of one MRZ character, measured on real specimens. */
        const val OCR_CHAR_BOX_MM = 3.5f

        /**
         * Estimates the frame from the MRZ words of a full-page OCR.
         *
         * - Direction: the first -> last character vector of each MRZ word.
         * - Scale: per MRZ line, distance from its first to its last character centre, divided by
         *   (ICAO line length - 1). The OCR character count is NOT used: it often drops or merges
         *   '<' fillers, which made the scale (and every zone) too large.
         * - Origin: first character of the top MRZ line.
         */
        fun fromWords(words: List<PageWord>, format: MrzFormat, mrzLines: List<String>? = null): MrzFrame? {
            val mrz = words.filter { it.isMrzToken }
            if (mrz.isEmpty()) return null
            var sx = 0.0
            var sy = 0.0
            for (w in mrz) {
                sx += (w.tail.cx - w.head.cx).toDouble()
                sy += (w.tail.cy - w.head.cy).toDouble()
            }
            val angle = atan2(sy, sx)
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            val vx = -uy
            val vy = ux
            fun pu(p: Pt) = p.x * ux + p.y * uy
            fun pv(p: Pt) = p.x * vx + p.y * vy
            fun Box.c() = Pt(cx, cy)

            // Group MRZ words into lines along v, then add the short pieces of each line ("<<<6").
            val tol = mrz.maxOf { it.head.height } * 0.7f
            val lines = mutableListOf<MutableList<PageWord>>()
            for (w in mrz.sortedBy { pv(it.head.c()) }) {
                val line = lines.lastOrNull()
                if (line != null && kotlin.math.abs(pv(w.head.c()) - pv(line[0].head.c())) < tol) line += w else lines += mutableListOf(w)
            }
            for (w in words) {
                if (w.isMrzToken || !w.isMrzPiece) continue
                lines.firstOrNull { l -> kotlin.math.abs(pv(w.head.c()) - pv(l[0].head.c())) < tol }?.add(w)
            }
            val ends = lines.map { line ->
                val first = line.minBy { pu(it.head.c()) }
                val last = line.maxBy { pu(it.tail.c()) }
                first.head.c() to last.tail.c()
            }
            // The longest measured line is the most complete one (data lines end with a check digit).
            val span = ends.maxOf { (a, b) -> pu(b) - pu(a) }
            if (span <= 0f) return null
            var pitch = span / (format.lineLength - 1)
            // Cross-check with the character height: on 23 of 24 real specimens the OCR box of one MRZ
            // character is 3.1-3.7 mm high (median 3.5). A much smaller scale from the span means the
            // OCR missed the end of the lines (e.g. printed over a hologram): average both estimates.
            val heights = mrz.flatMap { listOf(it.head.height, it.tail.height) }.sorted()
            val byHeight = heights[heights.size / 2] / OCR_CHAR_BOX_MM * PITCH_MM
            if (pitch < 0.85f * byHeight) pitch = (pitch + byHeight) / 2
            val pxPerMm = pitch / PITCH_MM
            // Which MRZ line each group is: the OCR can split a line into short pieces that never form a
            // group (Dutch ID card: only the name line was found), so the top group is not always line 1.
            val index = lines.map { line -> lineIndex(line.sortedBy { pu(it.head.c()) }.joinToString("") { it.text }, mrzLines) }
            val known = lines.indices.filter { index[it] != null }
            val spacing = when {
                known.size >= 2 && index[known.last()] != index[known.first()] ->
                    (pv(ends[known.last()].first) - pv(ends[known.first()].first)) / (index[known.last()]!! - index[known.first()]!!) / pxPerMm
                known.isEmpty() && ends.size >= 2 -> (pv(ends.last().first) - pv(ends.first().first)) / (ends.size - 1) / pxPerMm
                else -> LINE_PITCH_MM
            }.coerceIn(3.5f, 6f)

            // MRZ lines are left-aligned: the start is the left-most line start (the OCR sometimes loses
            // the beginning of one line), on the first line's baseline.
            val startU = ends.minOf { (a, _) -> pu(a) }
            val topLine = known.firstOrNull()
            val firstV = if (topLine != null) pv(ends[topLine].first) - index[topLine]!! * spacing * pxPerMm else pv(ends.first().first)
            val half = CHAR_HEIGHT_MM * pxPerMm / 2
            val cu = startU - 0.5f * pitch
            val cv = firstV - half
            val origin = Pt(cu * ux + cv * vx, cu * uy + cv * vy)
            return MrzFrame(origin, angle, pxPerMm, format, spacing)
        }

        /**
         * Index of the MRZ line [text] was read from, compared on the first 16 characters (the filler
         * runs at the end are often read as letters), when clearly closer to one line than to the others.
         */
        private fun lineIndex(text: String, mrzLines: List<String>?): Int? {
            if (mrzLines.isNullOrEmpty()) return null
            val t = text.uppercase().replace('«', '<').take(16)
            if (t.trimEnd('<').length < 8) return null
            val d = mrzLines.map { VizCrossCheck.levenshtein(t, it.take(16)) }
            val best = d.indices.minBy { d[it] }
            return best.takeIf { d[best] <= 5 && d.count { it == d[best] } == 1 }
        }

        /**
         * MRZ format guessed from the longest MRZ-like line, when no MRZ could be parsed (blurred line,
         * OCR errors): enough to place the anchor zones.
         */
        fun guessFormat(words: List<PageWord>): MrzFormat? {
            val longest = words.filter { it.isMrzToken }.maxOfOrNull { it.text.length } ?: return null
            return when {
                longest >= 40 -> MrzFormat.TD3
                longest >= 34 -> MrzFormat.TD2
                longest >= 26 -> MrzFormat.TD1
                else -> null
            }
        }

        /** Fallback when only the MRZ bounding box is known: no rotation. */
        fun fromBox(box: Box, format: MrzFormat): MrzFrame =
            MrzFrame(Pt(box.left, box.top), 0.0, box.width / format.lineLength / PITCH_MM, format)
    }
}

/**
 * Redaction rects are axis-aligned. A rotated quad is covered by horizontal strips (each strip spans
 * the quad's width at that height), which hugs a tilted zone much better than its bounding box.
 */
fun stripsCovering(quad: List<Pt>, strips: Int = 12): List<Box> {
    val bounds = Box.around(quad)
    val minY = bounds.top
    val maxY = bounds.bottom
    if (maxY - minY < 2f) return listOf(bounds)
    val edges = quad.indices.map { quad[it] to quad[(it + 1) % quad.size] }
    fun xRangeAt(y: Float): Pair<Float, Float>? {
        val xs = edges.mapNotNull { (a, b) ->
            if ((y < minOf(a.y, b.y)) || (y > maxOf(a.y, b.y)) || a.y == b.y) null
            else a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
        }
        return if (xs.isEmpty()) null else xs.min() to xs.max()
    }
    val step = (maxY - minY) / strips
    return (0 until strips).mapNotNull { i ->
        val y0 = minY + i * step
        val y1 = y0 + step
        val ranges = listOfNotNull(xRangeAt(y0 + 0.01f), xRangeAt(y1 - 0.01f), xRangeAt((y0 + y1) / 2))
        if (ranges.isEmpty()) null else Box(ranges.minOf { it.first }, y0, ranges.maxOf { it.second }, y1)
    }
}
