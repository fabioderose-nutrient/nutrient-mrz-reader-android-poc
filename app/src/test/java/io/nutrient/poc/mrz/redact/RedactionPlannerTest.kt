package io.nutrient.poc.mrz.redact

import io.nutrient.poc.mrz.mrz.MrzFormat
import io.nutrient.poc.mrz.mrz.MrzParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RedactionPlannerTest {

    private val td3 = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
    )
    private val mrz = MrzParser.parse(MrzFormat.TD3, td3)

    /**
     * Synthetic OCR words (head / tail = first / last character) for a TD3 page photographed at [angleDeg], [pxPerMm] pixels per mm.
     * Word geometry follows the page in mm, then is rotated around the page origin like a tilted photo.
     */
    private fun page(angleDeg: Double, pxPerMm: Float, offset: Pt = Pt(100f, 80f)): List<PageWord> {
        val a = Math.toRadians(angleDeg)
        fun img(x: Float, y: Float) = Pt(
            offset.x + (x * cos(a) - y * sin(a)).toFloat() * pxPerMm,
            offset.y + (x * sin(a) + y * cos(a)).toFloat() * pxPerMm,
        )
        fun rectBox(x0: Float, y0: Float, x1: Float, y1: Float) = Box.around(listOf(img(x0, y0), img(x1, y0), img(x1, y1), img(x0, y1)))
        val words = mutableListOf<PageWord>()
        fun word(text: String, line: Int, xMm: Float, yMm: Float, charMm: Float = 1.8f, hMm: Float = 2.6f) {
            val w = text.length * charMm
            val k = charMm
            words += PageWord(
                text, line, rectBox(xMm, yMm, xMm + w, yMm + hMm),
                rectBox(xMm, yMm, xMm + k, yMm + hMm), rectBox(xMm + w - k, yMm, xMm + w, yMm + hMm),
            )
        }
        // Labels and values (layout of the synthetic specimen, page mm).
        word("Surname", 1, 48f, 20f)
        word("ERIKSSON", 2, 48f, 23f)
        word("Given", 3, 48f, 28f); word("names", 3, 58f, 28f)
        word("ANNA", 4, 48f, 31f); word("MARIA", 4, 57f, 31f)
        word("Date", 5, 48f, 44f); word("of", 5, 56f, 44f); word("birth", 5, 60f, 44f); word("Sex", 5, 100f, 44f)
        word("12", 6, 48f, 47f); word("AUG", 6, 53f, 47f); word("74", 6, 70f, 47f); word("F", 6, 100f, 47f)
        word("Place", 7, 48f, 52f); word("of", 7, 58f, 52f); word("birth", 7, 62f, 52f)
        word("ZENITH", 8, 48f, 55f)
        word("Date", 9, 48f, 60f); word("of", 9, 56f, 60f); word("issue", 9, 60f, 60f)
        word("Date", 9, 88f, 60f); word("of", 9, 96f, 60f); word("expiry", 9, 100f, 60f)
        word("16", 10, 48f, 63f); word("APR", 10, 53f, 63f); word("07", 10, 68f, 63f)
        word("15", 10, 88f, 63f); word("APR", 10, 93f, 63f); word("12", 10, 108f, 63f)
        // MRZ: OCR-B pitch 2.54 mm, first character at (6, 71.4).
        word(td3[0], 11, 6f, 71.4f, charMm = 2.54f, hMm = 2.4f)
        word(td3[1], 12, 6f, 75.8f, charMm = 2.54f, hMm = 2.4f)
        return words
    }

    @Test fun frameFollowsScaleAndTilt() {
        for (angle in listOf(0.0, 7.0, -12.0)) {
            val frame = MrzFrame.fromWords(page(angle, 10f), MrzFormat.TD3)
            assertNotNull(frame)
            assertEquals("angle", angle, frame!!.angleDeg, 0.5)
            assertEquals("scale", 10f, frame.pxPerMm, 0.3f)
        }
    }

    @Test fun keywordsFindEachFieldInItsColumn() {
        val words = page(0.0, 10f)
        val frame = MrzFrame.fromWords(words, MrzFormat.TD3)
        val regions = RedactionPlanner.byKeywords(words, mrz, frame).associateBy { it.field }

        fun covered(field: RedactField) = regions.getValue(field).rects
        fun contains(field: RedactField, text: String) =
            words.filter { it.text == text }.any { w -> covered(field).any { r -> w.box.cx in r.left..r.right && w.box.cy in r.top..r.bottom } }

        assertTrue(contains(RedactField.SURNAME, "ERIKSSON"))
        assertTrue(contains(RedactField.GIVEN_NAMES, "MARIA"))
        assertTrue(contains(RedactField.DATE_OF_BIRTH, "AUG"))
        assertTrue(contains(RedactField.PLACE_OF_BIRTH, "ZENITH"))
        assertTrue(contains(RedactField.SEX, "F"))
        // Issue and expiry share a line: each must only take its own column.
        assertTrue(contains(RedactField.DATE_OF_ISSUE, "07"))
        assertTrue(!contains(RedactField.DATE_OF_ISSUE, "12"))
        assertTrue(contains(RedactField.DATE_OF_EXPIRY, "12"))
        // Labels themselves are not redacted.
        assertTrue(!contains(RedactField.SURNAME, "Surname"))
        assertTrue(RedactField.PHOTO in regions)
        assertTrue(RedactField.MRZ in regions)
    }

    @Test fun anchorZonesFollowATiltedPhoto() {
        val words = page(10.0, 8f)
        val frame = MrzFrame.fromWords(words, MrzFormat.TD3)!!
        val regions = RedactionPlanner.byMrzAnchor(words, frame).associateBy { it.field }
        val data = regions.getValue(RedactField.PERSONAL_DATA).rects
        // Every value word of the personal data zone is covered, even with a 10° tilt.
        for (text in listOf("ERIKSSON", "ZENITH", "AUG", "APR")) {
            val w = words.first { it.text == text }
            assertTrue(text, data.any { r -> w.box.cx in r.left..r.right && w.box.cy in r.top..r.bottom })
        }
        assertTrue(regions.getValue(RedactField.PHOTO).rects.size > 1) // strips, not one big box
    }

    @Test fun fuzzyDateDoesNotMatchUnrelatedWords() {
        assertEquals(8, io.nutrient.poc.mrz.mrz.VizCrossCheck.levenshtein("TYPETYPE", "12081974"))
        assertEquals(1, io.nutrient.poc.mrz.mrz.VizCrossCheck.levenshtein("12AUC74", "12AUG74"))
    }

    @Test fun stripsCoverARotatedQuad() {
        val quad = listOf(Pt(0f, 0f), Pt(100f, 20f), Pt(90f, 70f), Pt(-10f, 50f))
        val strips = stripsCovering(quad)
        for (p in quad.plus(Pt(45f, 35f))) {
            assertTrue("$p", strips.any { p.x in it.left - 0.5f..it.right + 0.5f && p.y in it.top - 0.5f..it.bottom + 0.5f })
        }
    }
}
