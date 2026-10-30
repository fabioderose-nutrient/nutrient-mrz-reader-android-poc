package io.nutrient.poc.mrz.mrz

import java.time.LocalDate

/** ICAO 9303 machine readable travel document formats. */
enum class MrzFormat(val lineCount: Int, val lineLength: Int, val label: String) {
    TD1(3, 30, "TD1 (ID card)"),
    TD2(2, 36, "TD2"),
    TD3(2, 44, "TD3 (passport)");
}

/** One character changed by the rule engine between the raw OCR text and the final MRZ. */
data class Correction(
    val line: Int,
    val index: Int,
    val from: Char,
    val to: Char,
    val reason: String,
)

/** A block of OCR lines that looks like an MRZ, already fitted to the ICAO line length. */
data class MrzCandidate(
    val format: MrzFormat,
    /** Indexes of the OCR text lines the MRZ came from (to find its bounding box). */
    val ocrLines: List<Int>,
    val rawLines: List<String>,
    val lines: List<String>,
    val corrections: List<Correction>,
)

data class CheckDigitResult(
    val field: String,
    val data: String,
    val expected: Char,
    val computed: Int?,
) {
    val valid: Boolean
        get() = computed != null && expected.digitToIntOrNull() == computed
}

data class MrzData(
    val format: MrzFormat,
    val lines: List<String>,
    val documentCode: String,
    val issuingState: String,
    val surname: String,
    val givenNames: String,
    val documentNumber: String,
    val nationality: String,
    val birthDateRaw: String,
    val sex: String,
    val expiryDateRaw: String,
    val optionalData1: String,
    val optionalData2: String,
    val checkDigits: List<CheckDigitResult>,
    val corrections: List<Correction> = emptyList(),
) {
    val allCheckDigitsValid: Boolean get() = checkDigits.all { it.valid }
    val validCheckDigitCount: Int get() = checkDigits.count { it.valid }

    val birthDate: LocalDate? get() = MrzDates.parseBirth(birthDateRaw)
    val expiryDate: LocalDate? get() = MrzDates.parseExpiry(expiryDateRaw)
}

object MrzDates {
    /** Birth dates: a two-digit year in the future belongs to the previous century. */
    fun parseBirth(raw: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val (yy, mm, dd) = split(raw) ?: return null
        val year = if (yy > today.year % 100) 1900 + yy else 2000 + yy
        return runCatching { LocalDate.of(year, mm, dd) }.getOrNull()
    }

    /** Expiry dates: travel documents issued under ICAO 9303 expire in 20xx. */
    fun parseExpiry(raw: String): LocalDate? {
        val (yy, mm, dd) = split(raw) ?: return null
        return runCatching { LocalDate.of(2000 + yy, mm, dd) }.getOrNull()
    }

    private fun split(raw: String): Triple<Int, Int, Int>? {
        if (raw.length != 6 || !raw.all { it.isDigit() }) return null
        return Triple(raw.substring(0, 2).toInt(), raw.substring(2, 4).toInt(), raw.substring(4, 6).toInt())
    }
}
