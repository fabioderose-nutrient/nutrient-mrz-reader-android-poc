package io.nutrient.poc.mrz.mrz

import java.text.Normalizer
import java.time.LocalDate

enum class VizStatus { FOUND, PARTIAL, NOT_FOUND }

data class VizMatch(val field: String, val mrzValue: String, val status: VizStatus, val evidence: String)

object VizText {
    // ICAO 9303 transliterations of letters that do not decompose into a base letter + accent.
    private val transliteration = mapOf(
        'Ø' to "OE", 'ø' to "oe", 'Æ' to "AE", 'æ' to "ae", 'Å' to "AA", 'å' to "aa", 'ß' to "ss",
        'Þ' to "TH", 'þ' to "th", 'Œ' to "OE", 'œ' to "oe", 'Ð' to "D", 'ð' to "d", 'Ł' to "L", 'ł' to "l",
    )

    /** Upper-case, ICAO transliteration, strip accents, keep letters, digits and single spaces. */
    fun normalize(text: String): String {
        val translit = buildString { text.forEach { c -> append(transliteration[c] ?: c) } }
        val noAccents = Normalizer.normalize(translit, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
    }
}

/**
 * Compares the MRZ with the visual inspection zone (VIZ), i.e. the printed text of the data page,
 * read by a full-page OCR. A mismatch can mean an OCR miss or a tampered MRZ: it is a hint for
 * the officer, not a verdict.
 */
object VizCrossCheck {


    fun run(mrz: MrzData, vizOcrText: String): List<VizMatch> {
        val text = VizText.normalize(vizOcrText)
        val words = text.split(' ').filter { it.isNotEmpty() }
        val compact = text.replace(" ", "")

        return listOf(
            names("Surname", mrz.surname, words, truncatedLast = false),
            names("Given names", mrz.givenNames, words, truncatedLast = true),
            documentNumber(mrz.documentNumber, compact),
            date("Date of birth", mrz.birthDate, mrz.birthDateRaw, compact),
            date("Date of expiry", mrz.expiryDate, mrz.expiryDateRaw, compact),
            country("Issuing state", mrz.issuingState, words, text),
            country("Nationality", mrz.nationality, words, text),
        )
    }

    private fun names(field: String, value: String, words: List<String>, truncatedLast: Boolean): VizMatch {
        val tokens = value.split(' ').filter { it.length >= 2 }
        if (tokens.isEmpty()) return VizMatch(field, value, VizStatus.NOT_FOUND, "nothing to compare")
        val found = tokens.mapIndexedNotNull { i, t ->
            val isLast = i == tokens.lastIndex
            words.firstOrNull { w ->
                w == t || (t.length >= 5 && levenshtein(w, t) <= 1) ||
                    (truncatedLast && isLast && t.length >= 4 && w.startsWith(t))
            }
        }
        val status = when (found.size) {
            tokens.size -> VizStatus.FOUND
            0 -> VizStatus.NOT_FOUND
            else -> VizStatus.PARTIAL
        }
        return VizMatch(field, value, status, if (found.isEmpty()) "not found in the printed text" else "read: ${found.joinToString(" ")}")
    }

    private fun documentNumber(value: String, compact: String): VizMatch {
        val v = value.replace(" ", "")
        val status = if (v.isNotEmpty() && compact.contains(v)) VizStatus.FOUND else VizStatus.NOT_FOUND
        return VizMatch("Document number", v, status, if (status == VizStatus.FOUND) "identique" else "not found in the printed text")
    }

    private fun date(field: String, date: LocalDate?, raw: String, compact: String): VizMatch {
        if (date == null) return VizMatch(field, raw, VizStatus.NOT_FOUND, "invalid MRZ date")
        val hit = PrintedDates.variants(date).firstOrNull { compact.contains(it) }
        return VizMatch(field, date.toString(), if (hit != null) VizStatus.FOUND else VizStatus.NOT_FOUND,
            hit?.let { "read as $it" } ?: "not found in the printed text")
    }

    private fun country(field: String, code: String, words: List<String>, text: String): VizMatch {
        if (code.isBlank()) return VizMatch(field, code, VizStatus.NOT_FOUND, "empty code")
        if (code in words) return VizMatch(field, code, VizStatus.FOUND, "code $code printed")
        val name = IcaoCountries.names(code).firstOrNull { it.length >= 4 && text.contains(it) }
        return if (name != null) VizMatch(field, code, VizStatus.FOUND, "'$name' printed")
        else VizMatch(field, code, VizStatus.NOT_FOUND, "neither the code nor the country name found")
    }

    /** Edit distance between [a] and [b] (insertions, deletions, substitutions). */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}

/** How a date can be printed on a data page, as compact upper-case strings (no spaces or separators). */
object PrintedDates {
    private val monthsEn = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val monthsFr = listOf("JANV", "FEVR", "MARS", "AVR", "MAI", "JUIN", "JUIL", "AOUT", "SEPT", "OCT", "NOV", "DEC")

    fun variants(date: LocalDate): List<String> {
        val dd = "%02d".format(date.dayOfMonth)
        val mm = "%02d".format(date.monthValue)
        val yyyy = date.year.toString()
        val yy = yyyy.takeLast(2)
        val en = monthsEn[date.monthValue - 1]
        val fr = monthsFr[date.monthValue - 1]
        return listOf(
            dd + en + yyyy, dd + en + yy, dd + fr + yyyy, dd + en + fr + yyyy, dd + fr + en + yyyy,
            dd + en + fr + yy, dd + fr + en + yy, dd + en + en + yyyy, dd + en + en + yy,
            dd + mm + yyyy, dd + mm + yy, yyyy + mm + dd,
        ).distinct()
    }
}
