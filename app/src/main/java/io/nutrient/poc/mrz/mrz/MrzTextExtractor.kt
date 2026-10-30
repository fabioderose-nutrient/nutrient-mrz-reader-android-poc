package io.nutrient.poc.mrz.mrz

/**
 * Turns raw OCR text (generic Nutrient OCR, not an MRZ engine) into MRZ candidates.
 *
 * Rules, in order:
 *  1. Clean each OCR line: upper-case, look-alikes of '<' mapped to '<', spaces and noise removed.
 *  2. Keep lines that look like MRZ (length, charset, fillers) and group consecutive ones
 *     into TD1 (3 x 30), TD2 (2 x 36) or TD3 (2 x 44) blocks.
 *  3. Fit every line to the exact ICAO length by stretching or shrinking its longest '<' run.
 *  4. Coerce each position to the type ICAO expects there (digit, letter, name, sex...).
 *  5. For alphanumeric fields protected by a check digit (document and personal numbers),
 *     try the ambiguous O/0, I/1, S/5, B/8, Z/2, G/6 swaps and keep the one that validates.
 *
 * Every change is recorded as a [Correction] so the UI can show what the rules did.
 */
object MrzTextExtractor {

    private val fillerLookAlikes = setOf('«', '‹', '〈', '≪', '<', '(', '[', '{', '£', '€', '¢', '‘', '’')

    // Position types per line. A: letter, N: digit, C: check digit (digit or '<'),
    // X: letter or digit, F: name, S: sex.
    private val layouts: Map<MrzFormat, List<String>> = mapOf(
        MrzFormat.TD3 to listOf(
            "AA" + "AAA" + "F".repeat(39),
            "X".repeat(9) + "N" + "AAA" + "N".repeat(6) + "N" + "S" + "N".repeat(6) + "N" + "X".repeat(14) + "C" + "N",
        ),
        MrzFormat.TD2 to listOf(
            "AA" + "AAA" + "F".repeat(31),
            "X".repeat(9) + "N" + "AAA" + "N".repeat(6) + "N" + "S" + "N".repeat(6) + "N" + "X".repeat(7) + "N",
        ),
        MrzFormat.TD1 to listOf(
            "AA" + "AAA" + "X".repeat(9) + "C" + "X".repeat(15),
            "N".repeat(6) + "N" + "S" + "N".repeat(6) + "N" + "AAA" + "X".repeat(11) + "N",
            "F".repeat(30),
        ),
    )

    private val toDigit = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0', 'I' to '1', 'L' to '1', 'T' to '7',
        'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6', 'A' to '4')
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B', '6' to 'G', '4' to 'A')
    private val ambiguous: Map<Char, List<Char>> = mapOf(
        '0' to listOf('O', 'D'), 'O' to listOf('0'), 'D' to listOf('0'), '1' to listOf('I', 'L'), 'I' to listOf('1'),
        'L' to listOf('1', '4'), '4' to listOf('L'), '5' to listOf('S'), 'S' to listOf('5'), '8' to listOf('B'), 'B' to listOf('8'),
        '2' to listOf('Z'), 'Z' to listOf('2'), '6' to listOf('G'), 'G' to listOf('6'),
    )

    private val fillerRun = Regex("[<KL]{3,}")

    /** Characters the generic OCR returns for the '<' filler glyph. */
    private const val FILLER_LOOKALIKES = "<KL"

    /**
     * The most plausible MRZ in [ocrText]: most valid check digits, weighed against how far the OCR
     * line lengths are from the format (TD2 and TD3 give the same check digits when the optional data
     * is empty), then the fewest corrections.
     */
    fun best(ocrText: String): Pair<MrzData, MrzCandidate>? = extract(ocrText)
        .map { MrzParser.parse(it.format, it.lines, it.corrections) to it }
        .maxWithOrNull(
            // One check digit outweighs 10 characters of length mismatch: a format cannot win on a line
            // that does not look like it (the Russian specimen: TD2 3/4 vs TD3 2/5, lines of 48 and 56).
            compareBy<Pair<MrzData, MrzCandidate>> { it.first.validCheckDigitCount - lengthGap(it.second) / 10.0 }
                .thenBy { -it.first.corrections.size },
        )

    private fun lengthGap(c: MrzCandidate) = c.rawLines.sumOf { kotlin.math.abs(it.length - c.format.lineLength) }

    /** Returns every plausible MRZ block found in [ocrText]. */
    fun extract(ocrText: String): List<MrzCandidate> {
        // Split on '\n' only, like NutrientOcrEngine, so line indexes match the OCR boxes.
        val ocrLines = ocrText.split('\n')
        // Virtual lines: an OCR line holding two long MRZ-like blocks separated by a space is two MRZ
        // lines merged by the OCR. Each virtual line remembers its OCR line index (for the boxes).
        val virtual = mutableListOf<Pair<String, Int>>()
        ocrLines.forEachIndexed { i, raw ->
            val tokens = raw.trim().split(Regex("\\s+")).map { clean(it) }.filter { it.length >= 20 && looksLikeMrz(it) }
            if (tokens.size >= 2) tokens.forEach { virtual += it to i } else virtual += clean(raw) to i
        }
        val cleaned = virtual.map { it.first }
        val candidates = mutableListOf<MrzCandidate>()

        for (i in cleaned.indices) {
            val ocrIndex = virtual[i].second
            // Two MRZ lines merged without any space: split in the middle.
            val merged = cleaned[i]
            if (looksLikeMrz(merged)) {
                for (f in listOf(MrzFormat.TD3, MrzFormat.TD2)) {
                    if (merged.length in (2 * f.lineLength - 3)..(2 * f.lineLength + 3)) {
                        val half = merged.length / 2
                        build(f, listOf(ocrIndex), listOf(merged.substring(0, half), merged.substring(half)))?.let { candidates += it }
                    }
                }
            }
            for (f in MrzFormat.entries) {
                if (i + f.lineCount > cleaned.size) continue
                val block = cleaned.subList(i, i + f.lineCount)
                if (!block.all { looksLikeMrz(it) }) continue
                val tolerance = if (f == MrzFormat.TD1) 5 else 9
                // Trailing fillers read as K/L inflate the length; they are re-fitted later anyway,
                // so only the part before them must fit the format.
                // Filler runs read as "K<K<KLK…" inflate the length; they are re-fitted later anyway, so a
                // run of 3+ filler look-alikes counts as one character here.
                if (block.all { it.length >= f.lineLength - tolerance && it.replace(fillerRun, "<").length <= f.lineLength + tolerance }) {
                    build(f, virtual.subList(i, i + f.lineCount).map { it.second }.distinct(), block)?.let { candidates += it }
                }
            }
        }
        return candidates.distinctBy { it.lines }
    }

    /** Upper-case, map filler look-alikes, drop everything outside [A-Z0-9<]. */
    internal fun clean(line: String): String {
        val sb = StringBuilder()
        for (raw in line.uppercase()) {
            val c = when {
                raw in fillerLookAlikes -> '<'
                raw == 'Ø' -> '0'
                else -> raw
            }
            if (c in 'A'..'Z' || c in '0'..'9' || c == '<') sb.append(c)
        }
        return sb.toString()
    }

    internal fun looksLikeMrz(line: String): Boolean {
        if (line.length < 24) return false
        val fillers = line.count { it == '<' }
        val digits = line.count { it.isDigit() }
        val kRuns = Regex("K{3,}").containsMatchIn(line)
        return fillers >= 2 || digits >= 10 || kRuns
    }

    private fun build(format: MrzFormat, ocrLines: List<Int>, rawBlock: List<String>): MrzCandidate? {
        val corrections = mutableListOf<Correction>()
        val raw = rawBlock.toMutableList()

        // Junk before the document code on line 1 (e.g. "1P<UTO..."): drop it.
        val l1 = raw[0]
        if (l1.isNotEmpty() && l1[0] !in "PIACV") {
            val idx = l1.indexOfFirst { it in "PIACV" }
            if (idx in 1..3) raw[0] = l1.substring(idx)
        }
        // "P<" read as "PK<" / "PL<" on a line that is too long.
        if (raw[0].length > format.lineLength && Regex("^[PIACV][KL]<").containsMatchIn(raw[0])) {
            raw[0] = raw[0].removeRange(1, 2)
        }

        val layout = layouts.getValue(format)
        val fitted = raw.mapIndexed { li, line ->
            val normalized = normalizeFillers(line, layout[li], format.lineLength)
            // The data line (dates, sex, nationality) is the second line in every format.
            val aligned = when (li) {
                0 -> alignStateCode(normalized)
                1 -> alignDataLine(format, normalized)
                else -> normalized
            }
            fitLength(aligned, format.lineLength)
        }
        val lines = fitted.mapIndexed { li, line -> coerce(line, layout[li], li, corrections) }.toMutableList()

        disambiguateWithCheckDigits(format, lines, corrections)
        repairCompositeWithFillers(format, lines, corrections)

        // Line 1 must start with a known document code letter.
        if (lines[0][0] !in "PIACV") return null
        return MrzCandidate(format, ocrLines, rawBlock, lines, corrections)
    }

    // Birth date + check digit, sex, expiry date + check digit: 15 characters with a fixed shape,
    // the most reliable anchor of the data line. Digits are matched with their usual OCR look-alikes.
    private const val D = "[0-9ODQUIZSBGLTA]"
    private val dateSexDate = Regex("$D{7}[MFX<HNEPR]$D{7}")

    /**
     * Re-aligns the data line on the birth/sex/expiry anchor. An extra or missing glyph before it
     * shifts every later field; here the part before the anchor is fixed to its exact length,
     * choosing the variant whose nationality is a real ICAO code and whose number check digit holds.
     */
    internal fun alignDataLine(format: MrzFormat, line: String): String {
        val expectedAt = if (format == MrzFormat.TD1) 0 else 13
        val tailLength = format.lineLength - expectedAt - 15
        // Every position (overlapping): a look-alike match ("0000000" + R + "US8B800") must not hide
        // the real one. Prefer real digits, a real sex code, then the expected position.
        val m = line.indices.mapNotNull { i -> dateSexDate.find(line, i)?.takeIf { it.range.first == i } }
            .maxWithOrNull(
                compareBy<MatchResult> { r -> r.value.count { it.isDigit() } }
                    .thenBy { r -> if (r.value[7] in "MFX<") 1 else 0 }
                    .thenBy { r -> -kotlin.math.abs(r.range.first - expectedAt) },
            ) ?: return line
        if (kotlin.math.abs(m.range.first - expectedAt) > 3) return line
        val head = line.substring(0, m.range.first)
        val tail = fitLength(line.substring(m.range.last + 1), tailLength)
        val fixedHead = when {
            format == MrzFormat.TD1 -> ""  // nothing precedes the birth date on a TD1 data line
            head.length == expectedAt -> head
            else -> bestHead(head, expectedAt)
        }
        return fixedHead + m.value + tail
    }

    // Fillers after a short state code are often read as K ("IDD<K<KLZ6311T47"): a K followed by '<'
    // is a filler, and when the run already holds one (or more fillers than the state code leaves
    // room for), so is a K right before the number.
    private val codeStateRest = Regex("^([PIACV][A-Z<])([A-Z]{1,3})((?:<|K(?=<))*)(K?)(?=[A-Z0-9])")

    /**
     * Line 1 always starts with the document code (2) and the issuing state (3, padded with '<', e.g.
     * "D<<"); the number (TD1) or the names (TD2/TD3) start at position 5. Extra or missing fillers
     * after a short state code are re-padded ("IDD<<<LZ63..." -> "IDD<<LZ63...").
     */
    internal fun alignStateCode(line: String): String {
        val m = codeStateRest.find(line) ?: return line
        val (code, state, fillers, k) = m.destructured
        val rest = line.substring(m.range.last + 1)
        return code + state.padEnd(3, '<') + (if ('K' in fillers || fillers.length > 3 - state.length) "" else k) + rest
    }

    /** Head of a TD3/TD2 data line = number (9) + check digit (1) + nationality (3). */
    private fun bestHead(head: String, target: Int): String {
        val variants = mutableListOf<String>()
        if (head.length > target) {
            val excess = head.length - target
            if (excess == 1) head.indices.forEach { variants += head.removeRange(it, it + 1) }
            if (excess == 2) for (a in head.indices) for (b in a + 1 until head.length) {
                variants += head.removeRange(b, b + 1).removeRange(a, a + 1)
            }
        } else {
            val missing = target - head.length
            if (missing <= 2) for (p in 0..head.length) variants += head.substring(0, p) + "<".repeat(missing) + head.substring(p)
        }
        fun score(h: String): Int {
            val nat = h.substring(10, 13).map { toLetter[it] ?: it }.joinToString("")
            val cd = toDigit[h[9]] ?: h[9]
            var s = 0
            if (IcaoCountries.isKnown(nat)) s += 2
            if (CheckDigit.compute(h.substring(0, 9)) == cd.digitToIntOrNull()) s += 2
            return s
        }
        return variants.maxByOrNull { score(it) } ?: head
    }

    /**
     * Repairs fillers before the line is fitted to its length (Tesseract reads '<' as K or L, and
     * sometimes a single '<' as "<K"):
     *  - in a run of '<', K and L, every K/L after the first '<' is a filler, unless it starts the
     *    next word ("<LLOYD", "<KAREN");
     *  - when the line is too long, a K right after '<' and before a letter is an extra glyph: dropped;
     *  - in name lines, 3+ fillers between two words collapse to the "<<" separator.
     * Positions are only approximate before fitting, so the field type is read from the line's
     * layout by proportion.
     */
    internal fun normalizeFillers(line: String, types: String, target: Int): String {
        // 3+ K/L in a row never occur in names or numbers: fillers read without any '<'.
        val sb = StringBuilder(Regex("[KL]{3,}").replace(line) { "<".repeat(it.value.length) })
        var i = 0
        while (i < sb.length) {
            if (sb[i] !in FILLER_LOOKALIKES) { i++; continue }
            var j = i
            while (j < sb.length && sb[j] in FILLER_LOOKALIKES) j++
            val firstFiller = (i until j).firstOrNull { sb[it] == '<' }
            if (firstFiller != null) {
                // Trailing K/L glued to the next word are kept as letters.
                var end = j
                if (j < sb.length && sb[j].isLetter()) {
                    while (end > firstFiller + 1 && sb[end - 1] != '<') end--
                }
                for (p in firstFiller + 1 until end) sb.setCharAt(p, '<')
            }
            i = j
        }
        var s = sb.toString()
        val extraK = Regex("<K(?=[A-JMN-Z])")
        while (s.length > target) {
            val m = extraK.find(s) ?: break
            s = s.removeRange(m.range.first + 1, m.range.last + 1)
        }
        val isNameLine = types.count { it == 'F' } > types.length / 2
        if (isNameLine) {
            s = Regex("(?<=[A-Z])<{3,}(?=[A-Z])").replace(s, "<<")
            // Every name field has a "<<" surname separator. If the OCR lost it, the first "K<" or "<K"
            // between two words is the separator read as K ("ERIKSSONK<ANNA" -> "ERIKSSON<<ANNA").
            val nameStart = types.indexOf('F').coerceAtLeast(0).coerceAtMost(s.length)
            val body = s.substring(nameStart).trimEnd('<')
            if ("<<" !in body) {
                val m = Regex("(?<=[A-Z])(K<|<KK?)(?=[A-Z])").find(body)
                if (m != null) s = s.substring(0, nameStart) + body.replaceRange(m.range, "<<") + s.substring(nameStart + body.length)
            }
        }
        return s
    }

    /** Stretches or shrinks the longest '<' run so the line has exactly [target] characters. */
    internal fun fitLength(line: String, target: Int): String {
        if (line.length == target) return line
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < line.length) {
            if (line[i] == '<') {
                var j = i
                while (j < line.length && line[j] == '<') j++
                if (j - i > bestLen) { bestLen = j - i; bestStart = i }
                i = j
            } else i++
        }
        var s = line
        if (s.length < target) {
            val add = "<".repeat(target - s.length)
            s = if (bestLen > 0) s.substring(0, bestStart) + add + s.substring(bestStart) else s + add
        } else {
            val excess = s.length - target
            if (bestLen > 1) {
                val remove = minOf(excess, bestLen - 1)
                s = s.substring(0, bestStart) + s.substring(bestStart + remove)
            }
            if (s.length > target) s = s.substring(0, target)
        }
        return s
    }

    private fun coerce(line: String, types: String, lineIndex: Int, out: MutableList<Correction>): String {
        val chars = line.toCharArray()

        // Runs of 3+ 'K' are fillers read as K by the OCR ("<<<" -> "KKK").
        Regex("K{3,}").findAll(line).forEach { m ->
            for (p in m.range) if (types[p] == 'F' || types[p] == 'X') set(chars, p, '<', lineIndex, out, "run of K = fillers")
        }

        for (p in chars.indices) {
            val c = chars[p]
            when (types[p]) {
                'N' -> if (!c.isDigit()) toDigit[c]?.let { set(chars, p, it, lineIndex, out, "numeric field") }
                'C' -> if (!c.isDigit() && c != '<') toDigit[c]?.let { set(chars, p, it, lineIndex, out, "check digit") }
                'A' -> if (c.isDigit()) toLetter[c]?.let { set(chars, p, it, lineIndex, out, "alphabetic field") }
                'F' -> when {
                    c.isDigit() -> set(chars, p, toLetter[c] ?: '<', lineIndex, out, "name field")
                }
                'S' -> if (c !in "MFX<") {
                    val fixed = when (c) { 'H', 'N', 'W' -> 'M'; 'E', 'P', 'R' -> 'F'; else -> '<' }
                    set(chars, p, fixed, lineIndex, out, "sex")
                }
            }
        }

        // A lone K/C/L squeezed between fillers is a filler too ("<K<<" -> "<<<<").
        for (p in chars.indices) {
            if (types[p] != 'F' && types[p] != 'X') continue
            val prev = if (p > 0) chars[p - 1] else ' '
            val next = if (p < chars.size - 1) chars[p + 1] else '<'
            if (chars[p] in "KCL" && prev == '<' && next == '<') set(chars, p, '<', lineIndex, out, "filler")
        }
        return String(chars)
    }

    private fun set(chars: CharArray, p: Int, to: Char, line: Int, out: MutableList<Correction>, reason: String) {
        if (chars[p] == to) return
        out += Correction(line, p, chars[p], to, reason)
        chars[p] = to
    }

    /**
     * Optional-data fields are mostly fillers; a stray glyph inside them ("<<<<H<6") only shows up in
     * the composite check digit. Try turning up to two such glyphs into '<' and keep it if the
     * composite then validates.
     */
    private fun repairCompositeWithFillers(format: MrzFormat, lines: MutableList<String>, out: MutableList<Correction>) {
        fun composite() = MrzParser.parse(format, lines).checkDigits.last().valid
        if (composite()) return
        val optional = when (format) {
            MrzFormat.TD3 -> listOf(1 to (28 until 42))
            MrzFormat.TD2 -> listOf(1 to (28 until 35))
            MrzFormat.TD1 -> listOf(0 to (15 until 30), 1 to (18 until 29))
        }
        val suspects = optional.flatMap { (line, range) ->
            val l = lines[line]
            if (range.count { l[it] == '<' } * 2 < range.count()) emptyList()
            else range.filter { l[it] != '<' && (l.getOrNull(it - 1) == '<' || l.getOrNull(it + 1) == '<') }.map { line to it }
        }.take(6)
        val combos = suspects.map { listOf(it) } + suspects.flatMapIndexed { i, a -> suspects.drop(i + 1).map { b -> listOf(a, b) } }
        for (combo in combos) {
            val saved = lines.toList()
            for ((line, p) in combo) lines[line] = lines[line].replaceRange(p, p + 1, "<")
            if (composite()) {
                for ((line, p) in combo) out += Correction(line, p, saved[line][p], '<', "check digit composite")
                return
            }
            for (i in lines.indices) lines[i] = saved[i]
        }
    }

    /** Fields guarded by a check digit: try ambiguous swaps until the check digit matches. */
    /**
     * Number fields guarded by a check digit. Look-alikes (O/0, I/1/L, S/5, B/8, Z/2, G/6) are tried
     * at every ambiguous position. A swap can leave a check digit valid by chance (O and 0 differ by
     * 24, B and 8 by 3...), so among the variants that pass the field check digit we prefer the one
     * that also passes the composite, then the one with the most digits after the first character
     * (passport numbers are mostly digits), then the fewest changes.
     */
    private fun disambiguateWithCheckDigits(format: MrzFormat, lines: MutableList<String>, out: MutableList<Correction>) {
        data class Field(val line: Int, val start: Int, val end: Int, val cdLine: Int, val cdPos: Int, val label: String)
        val fields = when (format) {
            MrzFormat.TD3 -> listOf(Field(1, 0, 9, 1, 9, "document number"), Field(1, 28, 42, 1, 42, "personal number"))
            MrzFormat.TD2 -> listOf(Field(1, 0, 9, 1, 9, "document number"))
            MrzFormat.TD1 -> listOf(Field(0, 5, 14, 0, 14, "document number"))
        }
        for (f in fields) {
            val cd = lines[f.cdLine][f.cdPos].digitToIntOrNull() ?: continue
            val value = lines[f.line].substring(f.start, f.end)
            val positions = value.indices.filter { value[it] in ambiguous }.take(8)
            if (positions.isEmpty()) continue

            fun withValue(v: String) = lines.toMutableList().also { it[f.line] = it[f.line].replaceRange(f.start, f.end, v) }
            fun compositeOk(v: String) = MrzParser.parse(format, withValue(v)).checkDigits.last().valid
            // A valid composite weighs as much as two changes: with many ambiguous glyphs, some far-off
            // combination often satisfies both check digits by chance.
            fun score(v: String, changes: Int) =
                listOf((if (compositeOk(v)) 2 else 0) - changes, v.drop(1).count { it.isDigit() }, -changes)

            var best: String? = null
            var bestScore: List<Int>? = null
            fun explore(k: Int, chars: CharArray, changes: Int) {
                if (k == positions.size) {
                    val v = String(chars)
                    if (CheckDigit.compute(v) == cd) {
                        val sc = score(v, changes)
                        if (bestScore == null || compareValues(sc, bestScore!!) > 0) { best = v; bestScore = sc }
                    }
                    return
                }
                val pos = positions[k]
                explore(k + 1, chars, changes)
                val original = chars[pos]
                for (alt in ambiguous.getValue(original)) {
                    chars[pos] = alt
                    explore(k + 1, chars, changes + 1)
                }
                chars[pos] = original
            }
            explore(0, value.toCharArray(), 0)
            val fixed = best ?: continue
            if (fixed == value) continue
            for (i in value.indices) if (value[i] != fixed[i]) {
                out += Correction(f.line, f.start + i, value[i], fixed[i], "check digit ${f.label}")
            }
            lines[f.line] = lines[f.line].replaceRange(f.start, f.end, fixed)
        }
    }

    private fun compareValues(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
}
