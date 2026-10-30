package io.nutrient.poc.mrz.mrz

/** ICAO 9303 check digit: weights 7-3-1, digits as-is, A-Z = 10-35, filler '<' = 0. */
object CheckDigit {
    private val weights = intArrayOf(7, 3, 1)

    fun compute(data: String): Int? {
        var sum = 0
        data.forEachIndexed { i, c ->
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                '<' -> 0
                else -> return null
            }
            sum += v * weights[i % 3]
        }
        return sum % 10
    }
}

/**
 * Splits fitted MRZ lines into fields and computes every check digit.
 * Input lines must already have the exact ICAO length (see [MrzTextExtractor]).
 */
object MrzParser {

    fun parse(format: MrzFormat, lines: List<String>, corrections: List<Correction> = emptyList()): MrzData {
        require(lines.size == format.lineCount && lines.all { it.length == format.lineLength }) {
            "Lines do not match ${format.name}"
        }
        return when (format) {
            MrzFormat.TD3 -> parseTd3Td2(format, lines, corrections, optionalEnd = 42)
            MrzFormat.TD2 -> parseTd3Td2(format, lines, corrections, optionalEnd = 35)
            MrzFormat.TD1 -> parseTd1(lines, corrections)
        }
    }

    private fun parseTd3Td2(
        format: MrzFormat,
        lines: List<String>,
        corrections: List<Correction>,
        optionalEnd: Int,
    ): MrzData {
        val l1 = lines[0]
        val l2 = lines[1]
        val (surname, given) = names(l1.substring(5))
        val checks = mutableListOf(
            check("Document number", l2.substring(0, 9), l2[9]),
            check("Date of birth", l2.substring(13, 19), l2[19]),
            check("Date of expiry", l2.substring(21, 27), l2[27]),
        )
        val optional = l2.substring(28, optionalEnd)
        if (format == MrzFormat.TD3) {
            // An empty personal number may carry '<' instead of 0 as its check digit.
            val personalCd = l2[42]
            if (!(personalCd == '<' && optional.all { it == '<' })) {
                checks += check("Personal number", optional, personalCd)
            }
        }
        val composite = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, optionalEnd + if (format == MrzFormat.TD3) 1 else 0)
        checks += check("Composite", composite, l2.last())

        return MrzData(
            format = format,
            lines = lines,
            documentCode = l1.substring(0, 2).trimFiller(),
            issuingState = l1.substring(2, 5).trimFiller(),
            surname = surname,
            givenNames = given,
            documentNumber = l2.substring(0, 9).trimFiller(),
            nationality = l2.substring(10, 13).trimFiller(),
            birthDateRaw = l2.substring(13, 19),
            sex = l2[20].toString().replace("<", "X"),
            expiryDateRaw = l2.substring(21, 27),
            optionalData1 = optional.trimFiller(),
            optionalData2 = "",
            checkDigits = checks,
            corrections = corrections,
        )
    }

    private fun parseTd1(lines: List<String>, corrections: List<Correction>): MrzData {
        val (l1, l2, l3) = lines
        var number = l1.substring(5, 14)
        var numberCd = l1[14]
        var optional1 = l1.substring(15, 30)
        // Long document numbers: '<' in the check digit slot, the number continues in optional data.
        if (numberCd == '<') {
            val end = optional1.indexOf('<').let { if (it < 0) optional1.length else it }
            if (end >= 2) {
                number += optional1.substring(0, end - 1)
                numberCd = optional1[end - 1]
                optional1 = optional1.substring(end)
            }
        }
        val (surname, given) = names(l3)
        val composite = l1.substring(5, 30) + l2.substring(0, 7) + l2.substring(8, 15) + l2.substring(18, 29)
        val checks = listOf(
            check("Document number", number, numberCd),
            check("Date of birth", l2.substring(0, 6), l2[6]),
            check("Date of expiry", l2.substring(8, 14), l2[14]),
            check("Composite", composite, l2[29]),
        )
        return MrzData(
            format = MrzFormat.TD1,
            lines = lines,
            documentCode = l1.substring(0, 2).trimFiller(),
            issuingState = l1.substring(2, 5).trimFiller(),
            surname = surname,
            givenNames = given,
            documentNumber = number.trimFiller(),
            nationality = l2.substring(15, 18).trimFiller(),
            birthDateRaw = l2.substring(0, 6),
            sex = l2[7].toString().replace("<", "X"),
            expiryDateRaw = l2.substring(8, 14),
            optionalData1 = optional1.trimFiller(),
            optionalData2 = l2.substring(18, 29).trimFiller(),
            checkDigits = checks,
            corrections = corrections,
        )
    }

    private fun check(field: String, data: String, expected: Char) =
        CheckDigitResult(field, data, expected, CheckDigit.compute(data))

    /** "ERIKSSON<<ANNA<MARIA<<<" -> ("ERIKSSON", "ANNA MARIA"). */
    internal fun names(field: String): Pair<String, String> {
        val trimmed = field.trimEnd('<')
        val sep = trimmed.indexOf("<<")
        val surname = if (sep >= 0) trimmed.substring(0, sep) else trimmed
        val given = if (sep >= 0) trimmed.substring(sep + 2) else ""
        return surname.replace('<', ' ').trim() to given.replace('<', ' ').trim()
    }

    private fun String.trimFiller() = trim('<').replace('<', ' ')
}
