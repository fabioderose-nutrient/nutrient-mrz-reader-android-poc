package io.nutrient.poc.mrz.mrz

import java.time.LocalDate
import java.time.Period

enum class CheckStatus { OK, WARNING, ERROR }

data class CheckItem(val group: String, val label: String, val status: CheckStatus, val detail: String)

/**
 * Consistency checks on a parsed MRZ. These are not a forgery detector: they flag MRZs that
 * cannot be genuine (bad check digits, impossible dates, unknown codes, broken structure).
 */
object IcaoChecks {
    const val GROUP_CHECK_DIGITS = "Check digits"
    const val GROUP_STRUCTURE = "Structure"
    const val GROUP_DATES = "Dates"
    const val GROUP_CODES = "Codes"

    fun run(mrz: MrzData, today: LocalDate = LocalDate.now()): List<CheckItem> =
        checkDigits(mrz) + structure(mrz) + dates(mrz, today) + codes(mrz)

    fun checkDigits(mrz: MrzData): List<CheckItem> = mrz.checkDigits.map {
        CheckItem(
            GROUP_CHECK_DIGITS, it.field,
            if (it.valid) CheckStatus.OK else CheckStatus.ERROR,
            "read '${it.expected}', computed '${it.computed ?: "?"}' on ${it.data}",
        )
    }

    fun structure(mrz: MrzData): List<CheckItem> {
        val items = mutableListOf<CheckItem>()
        val f = mrz.format
        val lengthsOk = mrz.lines.size == f.lineCount && mrz.lines.all { it.length == f.lineLength }
        items += CheckItem(
            GROUP_STRUCTURE, "Format ${f.name}",
            if (lengthsOk) CheckStatus.OK else CheckStatus.ERROR,
            "${f.lineCount} lines of ${f.lineLength} characters",
        )
        val charsetOk = mrz.lines.all { l -> l.all { it in 'A'..'Z' || it in '0'..'9' || it == '<' } }
        items += CheckItem(
            GROUP_STRUCTURE, "Character set",
            if (charsetOk) CheckStatus.OK else CheckStatus.ERROR, "A-Z, 0-9 and < only",
        )
        val code = mrz.documentCode.firstOrNull()
        val expected = if (f == MrzFormat.TD3) "P" else "IACV"
        items += CheckItem(
            GROUP_STRUCTURE, "Document type",
            if (code != null && code in expected) CheckStatus.OK else CheckStatus.WARNING,
            "'${mrz.documentCode}' (expected: ${expected.toList().joinToString("/")})",
        )
        items += CheckItem(
            GROUP_STRUCTURE, "Sex",
            if (mrz.sex in setOf("M", "F", "X")) CheckStatus.OK else CheckStatus.ERROR,
            "'${mrz.sex}'",
        )
        items += CheckItem(
            GROUP_STRUCTURE, "Surname / given names separator",
            if (mrz.surname.isNotBlank()) CheckStatus.OK else CheckStatus.ERROR,
            if (mrz.givenNames.isBlank()) "no given name (possible, to check)" else "'<<' found",
        )
        return items
    }

    fun dates(mrz: MrzData, today: LocalDate = LocalDate.now()): List<CheckItem> {
        val items = mutableListOf<CheckItem>()
        val birth = MrzDates.parseBirth(mrz.birthDateRaw, today)
        val expiry = MrzDates.parseExpiry(mrz.expiryDateRaw)

        items += if (birth == null) {
            CheckItem(GROUP_DATES, "Date of birth", CheckStatus.ERROR, "'${mrz.birthDateRaw}' is not a valid date")
        } else {
            val age = Period.between(birth, today).years
            when {
                birth.isAfter(today) -> CheckItem(GROUP_DATES, "Date of birth", CheckStatus.ERROR, "$birth is in the future")
                age > 120 -> CheckItem(GROUP_DATES, "Date of birth", CheckStatus.ERROR, "age $age")
                else -> CheckItem(GROUP_DATES, "Date of birth", CheckStatus.OK, "$birth (age $age)")
            }
        }
        items += if (expiry == null) {
            CheckItem(GROUP_DATES, "Date of expiry", CheckStatus.ERROR, "'${mrz.expiryDateRaw}' is not a valid date")
        } else when {
            expiry.isBefore(today) -> CheckItem(GROUP_DATES, "Date of expiry", CheckStatus.WARNING, "document expired on $expiry")
            expiry.isAfter(today.plusYears(11)) -> CheckItem(GROUP_DATES, "Date of expiry", CheckStatus.WARNING, "$expiry: validity > 10 years")
            else -> CheckItem(GROUP_DATES, "Date of expiry", CheckStatus.OK, "valid until $expiry")
        }
        if (birth != null && expiry != null) {
            items += CheckItem(
                GROUP_DATES, "Expiry after birth",
                if (expiry.isAfter(birth)) CheckStatus.OK else CheckStatus.ERROR,
                "$birth → $expiry",
            )
        }
        return items
    }

    fun codes(mrz: MrzData): List<CheckItem> = listOf(
        "Issuing state" to mrz.issuingState,
        "Nationality" to mrz.nationality,
    ).map { (label, code) ->
        val known = IcaoCountries.isKnown(code)
        CheckItem(
            GROUP_CODES, label,
            if (known) CheckStatus.OK else CheckStatus.ERROR,
            if (known) "$code = ${IcaoCountries.displayName(code)}" else "'$code' is not in the ICAO list",
        )
    }
}
