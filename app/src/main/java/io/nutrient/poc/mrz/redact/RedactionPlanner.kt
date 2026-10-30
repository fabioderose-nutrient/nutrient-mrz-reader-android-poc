package io.nutrient.poc.mrz.redact

import io.nutrient.poc.mrz.mrz.IcaoCountries
import io.nutrient.poc.mrz.mrz.MrzData
import io.nutrient.poc.mrz.mrz.MrzFormat
import io.nutrient.poc.mrz.mrz.PrintedDates
import io.nutrient.poc.mrz.mrz.VizCrossCheck
import kotlin.math.cos
import kotlin.math.sin

enum class RedactField(val label: String) {
    PHOTO("Photo"),
    SURNAME("Surname"),
    GIVEN_NAMES("Given names"),
    DOCUMENT_NUMBER("Document number"),
    NATIONALITY("Nationality"),
    DATE_OF_BIRTH("Date of birth"),
    SEX("Sex"),
    PLACE_OF_BIRTH("Place of birth"),
    DATE_OF_ISSUE("Date of issue"),
    DATE_OF_EXPIRY("Date of expiry"),
    AUTHORITY("Authority"),
    PERSONAL_NUMBER("Personal number"),
    ADDRESS("Address"),
    HEIGHT("Height"),
    PERSONAL_DATA("Personal data zone"),
    HEADER("Header zone (number, codes)"),
    BACK_DATA("Data above the MRZ"),
    MRZ("MRZ"),
}

enum class RedactMethod(val label: String) { KEYWORDS("Keywords"), MRZ_ANCHOR("MRZ anchor") }

enum class RedactSource(val label: String) {
    MRZ_VALUE("value read in the MRZ"),
    LABEL("value under a printed label"),
    MRZ_GEOMETRY("position computed from the MRZ"),
}

data class RedactionRegion(val field: RedactField, val source: RedactSource, val rects: List<Box>)

/**
 * Decides what to redact on an upright data page.
 *
 * - [byKeywords]: field by field. Values known from the MRZ (names, number, dates, nationality) are
 *   searched in the page OCR; fields that are not in the MRZ (place of birth, date of issue,
 *   authority, sex, address, height) are found under their printed label, on the next visual line,
 *   in the label's column, and must look like that field (a sex is M/F/X, a date has digits…).
 *   The photo comes from the MRZ geometry.
 * - [byMrzAnchor]: zone by zone. ICAO 9303 fixes where the photo and the personal data sit relative
 *   to the MRZ; the zones are placed in the [MrzFrame], so scale and tilt of the photo are followed.
 *
 * Text lines are rebuilt from word positions (in the MRZ frame's rotation): the OCR's own line
 * numbers often merge a label and its value, or two visual lines.
 */
object RedactionPlanner {
    /** Optional trace of the keyword decisions (tests only). */
    var debug: ((String) -> Unit)? = null

    private val labels: Map<RedactField, List<String>> = mapOf(
        RedactField.SURNAME to listOf("SURNAME", "NOM", "NAME", "NACHNAME", "APELLIDOS", "COGNOME", "ACHTERNAAM", "SOYADI", "NAZWISKO", "SOBRENOME", "ETTERNAVN", "EFTERNAVN", "EFTERNAMN", "SUKUNIMI"),
        RedactField.GIVEN_NAMES to listOf("GIVEN NAMES", "GIVEN NAME", "FORENAMES", "FIRST AND MIDDLE NAMES", "PRENOMS", "VORNAMEN", "NOMBRES", "NOME", "VOORNAMEN", "IMIONA", "FORNAVN", "FORNAMN", "ETUNIMET"),
        RedactField.DOCUMENT_NUMBER to listOf("PASSPORT NO", "PASSPORT NUMBER", "DOCUMENT NO", "DOCUMENT NUMBER", "DU PASSEPORT", "PASS NR", "PASSNUMMER", "PASAPORTE NO"),
        RedactField.NATIONALITY to listOf("NATIONALITY", "NATIONALITE", "STAATSANGEHORIGKEIT", "NACIONALIDAD", "CITTADINANZA", "NATIONALITEIT", "UYRUGU", "OBYWATELSTWO", "NACIONALIDADE", "NASJONALITET", "NATIONALITET"),
        RedactField.DATE_OF_BIRTH to listOf("DATE OF BIRTH", "DATE DE NAISSANCE", "GEBURTSTAG", "GEBURTSDATUM", "FECHA DE NACIMIENTO", "DATA DI NASCITA", "GEBOORTEDATUM", "DOGUM TARIHI", "DATA URODZENIA", "DATA DE NASCIMENTO", "FODSELSDATO"),
        RedactField.SEX to listOf("SEX", "SEXE", "GESCHLECHT", "SEXO", "SESSO", "GESLACHT", "CINSIYETI", "PLEC", "KJONN", "KON"),
        RedactField.PLACE_OF_BIRTH to listOf("PLACE OF BIRTH", "LIEU DE NAISSANCE", "GEBURTSORT", "LUGAR DE NACIMIENTO", "LUOGO DI NASCITA", "GEBOORTEPLAATS", "DOGUM YERI", "MIEJSCE URODZENIA", "LOCAL DE NASCIMENTO", "FODESTED"),
        RedactField.DATE_OF_ISSUE to listOf("DATE OF ISSUE", "DATE DE DELIVRANCE", "AUSSTELLUNGSDATUM", "DATE OF ISSUANCE", "ISSUED ON", "FECHA DE EXPEDICION", "DATA DI RILASCIO", "DATUM VAN AFGIFTE", "VERILIS TARIHI", "DATA WYDANIA", "DATA DE EMISSAO", "UTSTEDT DATO"),
        RedactField.DATE_OF_EXPIRY to listOf("DATE OF EXPIRY", "DATE D EXPIRATION", "EXPIRY DATE", "VALID UNTIL", "GULTIG BIS", "FECHA DE CADUCIDAD", "DATA DI SCADENZA", "GELDIG TOT", "GECERLILIK TARIHI", "DATA WAZNOSCI", "VALIDO ATE"),
        RedactField.AUTHORITY to listOf("AUTHORITY", "ISSUING AUTHORITY", "AUTORITE", "BEHORDE", "AUTORIDAD", "AUTORITA", "AFGEGEVEN DOOR", "MAKAM", "ORGAN WYDAJACY", "UTSTEDENDE MYNDIGHET"),
        RedactField.ADDRESS to listOf("ADDRESS", "ADRESSE", "ANSCHRIFT", "DOMICILIO", "INDIRIZZO", "ADRES", "MORADA"),
        RedactField.HEIGHT to listOf("HEIGHT", "TAILLE", "GROSSE", "ESTATURA", "STATURA", "LENGTE", "WZROST", "HOYDE"),
    )
    private val stopWords = setOf("OF", "DE", "D", "DU", "DI", "DA", "DEL", "VAN", "LA", "LE", "DER", "DES", "OG")
    private val labelTokens: Set<String> = labels.values.flatten().flatMap { it.split(' ') }.filter { it.length >= 3 }.toSet()

    /** How many visual lines a value can take below its label. */
    private fun maxLines(f: RedactField) = when (f) {
        RedactField.ADDRESS -> 4
        RedactField.SURNAME, RedactField.AUTHORITY -> 2
        else -> 1
    }

    /** Does this word look like a value of [f]? Rejects e.g. a height line taken for a missing sex. */
    private fun accepts(f: RedactField, w: PageWord): Boolean {
        val n = w.norm
        if (n.isEmpty()) return false
        val tokens = n.split(' ')
        return when (f) {
            RedactField.SEX -> tokens.all { it in setOf("M", "F", "X", "H", "K") }
            RedactField.DATE_OF_BIRTH, RedactField.DATE_OF_ISSUE, RedactField.DATE_OF_EXPIRY -> n.any { it.isDigit() } || n.length in 3..5
            RedactField.HEIGHT -> n.any { it.isDigit() } || n == "M" || n == "CM"
            RedactField.DOCUMENT_NUMBER -> n.count { it.isDigit() } >= 3
            RedactField.NATIONALITY, RedactField.SURNAME, RedactField.GIVEN_NAMES -> n.any { it.isLetter() }
            else -> true
        }
    }

    // Page zones in millimetres (page top-left origin), generous on purpose, and where the first MRZ
    // character sits on that page. Values follow the ICAO 9303 layouts of each format.
    private class Template(val mrzOrigin: Pt, val zones: List<Pair<RedactField, Box>>)

    private val templates = mapOf(
        MrzFormat.TD3 to Template(
            Pt(6f, 71.4f),
            listOf(
                RedactField.PHOTO to Box(2f, 13f, 42f, 70.5f),
                RedactField.PERSONAL_DATA to Box(40f, 13f, 124f, 70.5f),
                RedactField.HEADER to Box(2f, 2f, 124f, 14f),
            ),
        ),
        MrzFormat.TD2 to Template(
            Pt(3f, 59.5f),
            listOf(
                RedactField.PHOTO to Box(2f, 10f, 36f, 58f),
                RedactField.PERSONAL_DATA to Box(34f, 10f, 104f, 58f),
                RedactField.HEADER to Box(2f, 2f, 104f, 11f),
            ),
        ),
        // TD1: the MRZ is on the back of the card; everything printed above it is personal data.
        MrzFormat.TD1 to Template(Pt(3f, 36.5f), listOf(RedactField.BACK_DATA to Box(1f, 1f, 85f, 35.5f))),
    )

    fun plan(method: RedactMethod, words: List<PageWord>, mrz: MrzData?, frame: MrzFrame?, width: Float, height: Float): List<RedactionRegion> {
        val regions = when (method) {
            RedactMethod.KEYWORDS -> byKeywords(words, mrz, frame)
            RedactMethod.MRZ_ANCHOR -> byMrzAnchor(words, frame)
        }
        return regions.map { r -> r.copy(rects = r.rects.map { it.clip(width, height) }.filterNot { it.isEmpty }) }
            .filter { it.rects.isNotEmpty() }
    }

    // --- MRZ anchor ---------------------------------------------------------------------------------

    fun byMrzAnchor(words: List<PageWord>, frame: MrzFrame?): List<RedactionRegion> {
        frame ?: return emptyList()
        val t = templates.getValue(frame.format)
        val zones = t.zones.map { (field, z) ->
            val quad = frame.quad(z.left - t.mrzOrigin.x, z.top - t.mrzOrigin.y, z.right - t.mrzOrigin.x, z.bottom - t.mrzOrigin.y)
            RedactionRegion(field, RedactSource.MRZ_GEOMETRY, cover(quad, frame))
        }
        return zones + listOfNotNull(mrzRegion(words, frame))
    }

    private fun cover(quad: List<Pt>, frame: MrzFrame) =
        if (kotlin.math.abs(frame.angleDeg) < 1.5) listOf(Box.around(quad)) else stripsCovering(quad)

    /** The MRZ lines: the full ICAO line length from the frame, plus whatever the OCR boxed. */
    private fun mrzRegion(words: List<PageWord>, frame: MrzFrame?): RedactionRegion? {
        val strong = words.filter { it.isMrzToken }
        val band = if (strong.isEmpty()) emptyList() else words.filter { w ->
            !w.isMrzToken && w.isMrzPiece && strong.any { kotlin.math.abs(it.box.cy - w.box.cy) < it.box.height * 0.6f }
        }
        val tokens = strong + band
        val rects = mutableListOf<Box>()
        // On a tilted photo the axis-aligned box of a whole MRZ line is huge and spills over the data
        // above: there, the strips computed from the frame are the MRZ cover.
        val tilted = frame != null && kotlin.math.abs(frame.angleDeg) >= 1.5
        if (tokens.isNotEmpty() && !tilted) {
            val pad = tokens.maxOf { it.box.height } * 0.25f
            rects += tokens.map { it.box.inflate(pad) }
        }
        if (frame != null) {
            val f = frame.format
            val bottom = (f.lineCount - 1) * frame.lineSpacingMm + MrzFrame.CHAR_HEIGHT_MM + 1.2f
            rects += cover(frame.quad(-1.5f, -1.2f, f.lineLength * MrzFrame.PITCH_MM + 1f, bottom), frame)
        }
        if (rects.isEmpty()) return null
        return RedactionRegion(RedactField.MRZ, if (frame != null) RedactSource.MRZ_GEOMETRY else RedactSource.MRZ_VALUE, rects)
    }

    // --- Keywords -----------------------------------------------------------------------------------

    /** A word in the de-rotated page coordinates (so "below" and "same line" work on tilted photos). */
    private class W(val w: PageWord, angle: Double) {
        val cx: Float
        val cy: Float
        val h = w.box.height.coerceAtLeast(4f)
        private val halfW = w.box.width / 2
        init {
            val c = cos(-angle).toFloat()
            val s = sin(-angle).toFloat()
            cx = w.box.cx * c - w.box.cy * s
            cy = w.box.cx * s + w.box.cy * c
        }
        val x0 get() = cx - halfW
        val x1 get() = cx + halfW
        val y0 get() = cy - h / 2
        val y1 get() = cy + h / 2
        val norm get() = w.norm
    }

    /** Visual lines: words whose vertical centres are close, sorted left to right. */
    private fun visualLines(words: List<W>): List<List<W>> {
        val lines = mutableListOf<MutableList<W>>()
        for (w in words.sortedBy { it.cy }) {
            val line = lines.lastOrNull()
            if (line != null && kotlin.math.abs(w.cy - line.map { it.cy }.average().toFloat()) < 0.45f * maxOf(w.h, line.maxOf { it.h })) line += w
            else lines += mutableListOf(w)
        }
        return lines.map { it.sortedBy { w -> w.cx } }
    }

    fun byKeywords(words: List<PageWord>, mrz: MrzData?, frame: MrzFrame?): List<RedactionRegion> {
        val angle = frame?.angleRad ?: 0.0
        val all = words.filterNot { it.isMrzToken || it.norm.isEmpty() }.map { W(it, angle) }
        val lines = visualLines(all)
        // Prose (the notes printed on a card back, the request text of a passport) is not a field value:
        // a "DE" or "NEDERLANDEN" there must not be redacted as a name or a nationality.
        val prose = lines.filter { it.size > 8 }.flatten().toSet()
        val text = all.filterNot { it in prose }
        val out = mutableListOf<RedactionRegion>()

        // 1. Values read in the MRZ, searched in the printed text.
        val byValue = mutableMapOf<RedactField, List<PageWord>>()
        if (mrz != null) {
            byValue[RedactField.SURNAME] = nameWords(mrz.surname, text, truncated = false)
            byValue[RedactField.GIVEN_NAMES] = nameWords(mrz.givenNames, text, truncated = true)
            byValue[RedactField.DOCUMENT_NUMBER] = idWords(mrz.documentNumber, text)
            if (mrz.optionalData1.replace(" ", "").length >= 5) byValue[RedactField.PERSONAL_NUMBER] = idWords(mrz.optionalData1, text)
            val fieldLines = lines.map { l -> l.filterNot { it in prose } }.filter { it.isNotEmpty() }
            mrz.birthDate?.let { byValue[RedactField.DATE_OF_BIRTH] = dateWords(PrintedDates.variants(it), fieldLines) }
            mrz.expiryDate?.let { byValue[RedactField.DATE_OF_EXPIRY] = dateWords(PrintedDates.variants(it), fieldLines) }
        }

        // 2. Printed labels: the value is on the next visual line(s), in the same column.
        val byLabel = labels.mapValues { (field, phrases) -> valueUnderLabel(field, phrases, lines) }

        // Nationality: the label is the reliable source (the country name also appears in the header).
        if (mrz != null && byLabel[RedactField.NATIONALITY].isNullOrEmpty()) {
            byValue[RedactField.NATIONALITY] = nationalityWords(mrz.nationality, lines.map { l -> l.filterNot { it in prose } })
        }

        for (field in RedactField.entries) {
            val fromValue = byValue[field].orEmpty()
            val fromLabel = byLabel[field].orEmpty()
            val chosen = (fromValue + fromLabel).distinct()
            if (chosen.isEmpty()) continue
            val pad = chosen.maxOf { it.box.height } * 0.2f
            val source = if (fromValue.isNotEmpty()) RedactSource.MRZ_VALUE else RedactSource.LABEL
            out += RedactionRegion(field, source, chosen.map { it.box.inflate(pad) })
        }

        // 3. Photo: no text to find, so it comes from the MRZ geometry.
        if (frame != null) out += byMrzAnchor(words, frame).filter { it.field == RedactField.PHOTO }
        mrzRegion(words, frame)?.let { out += it }
        return out
    }

    private fun isLabelWord(w: W) = w.norm.split(' ').any { it in labelTokens }

    private fun nameWords(value: String, words: List<W>, truncated: Boolean): List<PageWord> {
        // Two-letter tokens ("DE", "LA") are everyday words: too risky to match on their own.
        val tokens = value.split(' ').filter { it.length >= 3 }
        return tokens.flatMapIndexed { i, t ->
            val last = i == tokens.lastIndex
            words.filter { w ->
                !isLabelWord(w) && w.norm.split(' ').any { n ->
                    n == t || (t.length >= 5 && VizCrossCheck.levenshtein(n, t) <= (if (t.length >= 8) 2 else 1)) ||
                        (truncated && last && t.length >= 4 && n.startsWith(t))
                }
            }.map { it.w }
        }
    }

    private fun idWords(value: String, words: List<W>): List<PageWord> {
        val v = value.replace(" ", "")
        if (v.length < 4) return emptyList()
        return words.filter { w ->
            val c = w.norm.replace(" ", "")
            c.contains(v) || (v.length >= 6 && c.length >= v.length - 1 && VizCrossCheck.levenshtein(c, v) <= 1)
        }.map { it.w }
    }

    /**
     * Dates are often split ("12 AUG/AOUT 74"): match runs of up to 4 consecutive words on a visual
     * line, exactly, or with OCR errors on letters only ("12 AUG/AQUT 74"): digits must be identical,
     * otherwise "15 APR 2014" would pass for "15 APR 2024".
     */
    private fun dateWords(variants: List<String>, lines: List<List<W>>): List<PageWord> {
        val longest = variants.maxOf { it.length }
        var fuzzy: List<PageWord>? = null
        for (ws in lines) {
            for (i in ws.indices) {
                var acc = ""
                for (j in i until minOf(ws.size, i + 4)) {
                    acc += ws[j].norm.replace(" ", "")
                    // An all-digit form ("120864") only counts when printed with separators: any
                    // number on the page would match otherwise.
                    val raw = ws.subList(i, j + 1).joinToString(" ") { it.w.text }
                    if (acc.all { it.isDigit() } && raw.none { it in "./- " }) continue
                    if (acc in variants) return ws.subList(i, j + 1).map { it.w }
                    if (fuzzy == null && acc.length >= 8 && variants.any {
                            it.length >= 8 && it.filter(Char::isDigit) == acc.filter(Char::isDigit) && VizCrossCheck.levenshtein(acc, it) <= 2
                        }
                    ) fuzzy = ws.subList(i, j + 1).map { it.w }
                    if (acc.length > longest + 2) break
                }
            }
        }
        return fuzzy.orEmpty()
    }

    /**
     * Nationality without a readable label: the adjective sharing the country's stem (UTOPIAN,
     * FRANCAISE, DEUTSCH, NORWEGIAN), not the country name itself, which also appears in headers.
     */
    private fun nationalityWords(code: String, lines: List<List<W>>): List<PageWord> {
        val names = IcaoCountries.names(code)
        val stems = names.filter { it.length >= 5 && ' ' !in it }.map { it.take(4) }
        return lines.flatten().filter { w ->
            w.norm.length >= 5 && w.norm !in names && stems.any { w.norm.startsWith(it) } && !isLabelWord(w)
        }.map { it.w }
    }

    private class LabelHit(val line: Int, val first: Int, val last: Int, val x0: Float, val x1: Float, val y1: Float, val h: Float)

    private fun findLabels(phrases: List<String>, lines: List<List<W>>): List<LabelHit> {
        val hits = mutableListOf<LabelHit>()
        lines.forEachIndexed { ln, ws ->
            // Flatten to (token, word index): one OCR word may hold several tokens and vice versa.
            // Small words ("of", "de"…) are often dropped by the OCR: they are ignored on both sides.
            val tokens = ws.flatMapIndexed { wi, w -> w.norm.split(' ').filter { it.isNotEmpty() && it !in stopWords }.map { it to wi } }
            for (phrase in phrases.map { p -> p.split(' ').filter { it !in stopWords } }) {
                for (start in 0..tokens.size - phrase.size) {
                    val ok = phrase.indices.all { k ->
                        val t = tokens[start + k].first
                        t == phrase[k] || (phrase[k].length >= 5 && VizCrossCheck.levenshtein(t, phrase[k]) <= 1)
                    }
                    if (!ok) continue
                    // Grow to the whole label block ("Date of issue / Date de délivrance"): the column
                    // starts at the block, whichever language the match was found in.
                    var first = tokens[start].second
                    var last = tokens[start + phrase.size - 1].second
                    val h = ws[first].h
                    while (first > 0 && ws[first].x0 - ws[first - 1].x1 < 1.2f * h) first--
                    while (last < ws.lastIndex && ws[last + 1].x0 - ws[last].x1 < 1.2f * h) last++
                    val block = ws.subList(first, last + 1)
                    hits += LabelHit(ln, first, last, block.minOf { it.x0 }, block.maxOf { it.x1 }, block.maxOf { it.y1 }, h)
                }
            }
        }
        return hits.distinctBy { it.line to it.first }
    }

    private fun valueUnderLabel(field: RedactField, phrases: List<String>, lines: List<List<W>>): List<PageWord> {
        val result = mutableListOf<PageWord>()
        for (hit in findLabels(phrases, lines)) {
            debug?.invoke("$field label at line ${hit.line} '${lines[hit.line].subList(hit.first, hit.last + 1).joinToString(" ") { it.w.text }}'")
            val h = hit.h
            val labelLine = lines[hit.line]
            // Column: from the label block to the next text block on the label line.
            val colStart = hit.x0 - h
            val colEnd = labelLine.drop(hit.last + 1).firstOrNull { it.x0 > hit.x1 + 1.2f * h }?.x0?.minus(0.3f * h) ?: Float.MAX_VALUE
            var taken = 0
            var lastBottom = hit.y1
            for (ln in hit.line + 1 until lines.size) {
                val inColumn = lines[ln].filter { it.cx in colStart..colEnd }
                if (inColumn.isEmpty()) continue
                val top = inColumn.minOf { it.y0 }
                if (top > lastBottom + 2.2f * h) break
                // A label line (second line of a multi-line label, or the next field): skip it before
                // the value, stop after it.
                if (inColumn.any { isLabelWord(it) } && inColumn.all { isLabelWord(it) || !accepts(field, it.w) || it.norm.length <= 2 }) {
                    if (taken > 0) break
                    lastBottom = inColumn.maxOf { it.y1 }
                    continue
                }
                val value = inColumn.filter { !isLabelWord(it) && accepts(field, it.w) }
                debug?.invoke("   line $ln col[${colStart.toInt()},${if (colEnd > 1e9) "inf" else colEnd.toInt().toString()}]: ${inColumn.joinToString(" ") { it.w.text }} -> ${value.joinToString(" ") { it.w.text }}")
                if (value.isEmpty()) break
                result += value.map { it.w }
                lastBottom = value.maxOf { it.y1 }
                if (++taken >= maxLines(field)) break
            }
        }
        return result.distinct()
    }
}
