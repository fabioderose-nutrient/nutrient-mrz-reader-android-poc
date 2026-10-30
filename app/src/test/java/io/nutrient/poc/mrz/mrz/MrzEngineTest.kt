package io.nutrient.poc.mrz.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** ICAO 9303 specimen documents (Utopia, Anna Maria Eriksson). */
class MrzEngineTest {

    private val td3 = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
    )
    private val td1 = listOf(
        "I<UTOD231458907<<<<<<<<<<<<<<<",
        "7408122F1204159UTO<<<<<<<<<<<6",
        "ERIKSSON<<ANNA<MARIA<<<<<<<<<<",
    )
    private val td2 = listOf(
        "I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<",
        "D231458907UTO7408122F1204159<<<<<<<6",
    )

    private fun best(text: String): MrzData {
        val best = MrzTextExtractor.best(text)
        assertTrue("no MRZ candidate in:\n$text", best != null)
        return best!!.first
    }

    @Test fun checkDigit() {
        assertEquals(6, CheckDigit.compute("L898902C3"))
        assertEquals(2, CheckDigit.compute("740812"))
        assertEquals(9, CheckDigit.compute("120415"))
    }

    @Test fun td3Clean() {
        val m = best(td3.joinToString("\n"))
        assertEquals(MrzFormat.TD3, m.format)
        assertEquals("ERIKSSON", m.surname)
        assertEquals("ANNA MARIA", m.givenNames)
        assertEquals("L898902C3", m.documentNumber)
        assertEquals("UTO", m.nationality)
        assertEquals(LocalDate.of(1974, 8, 12), m.birthDate)
        assertEquals(LocalDate.of(2012, 4, 15), m.expiryDate)
        assertEquals("F", m.sex)
        assertTrue(m.allCheckDigitsValid)
        assertTrue(m.corrections.isEmpty())
    }

    @Test fun td1Clean() {
        val m = best(td1.joinToString("\n"))
        assertEquals(MrzFormat.TD1, m.format)
        assertEquals("D23145890", m.documentNumber)
        assertEquals("ERIKSSON", m.surname)
        assertTrue(m.checkDigits.joinToString { "${it.field}:${it.valid}" }, m.allCheckDigitsValid)
    }

    @Test fun td2Clean() {
        val m = best(td2.joinToString("\n"))
        assertEquals(MrzFormat.TD2, m.format)
        assertEquals("D23145890", m.documentNumber)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun td3WithTypicalOcrNoise() {
        // Spaces, « for <, K for fillers, O for 0 in the number, S for 5 in a date, junk line around.
        val noisy = """
            PASSPORT  UTOPIA
            P<UTOERIKSSON«<ANNA<MARIA<<<<<KKKK<<<<<<<<<
            L898902C36UTO74O8122F12O4l59ZE184226B<<<<<10
        """.trimIndent()
        val m = best(noisy)
        assertEquals("ERIKSSON", m.surname)
        assertEquals("ANNA MARIA", m.givenNames)
        assertEquals("740812", m.birthDateRaw)
        assertEquals("120415", m.expiryDateRaw)
        assertTrue(m.checkDigits.joinToString { "${it.field}:${it.valid}" }, m.allCheckDigitsValid)
        assertTrue(m.corrections.isNotEmpty())
    }

    @Test fun realNutrientOcrOutput() {
        // Verbatim text returned by the Nutrient Android OCR (11.7.0, English) on the synthetic TD3 specimen.
        val ocr = """
            15 APR 12
            P<UTOERIKSSON<K<ANNA<KMARIA<KLLLLLLLLLLLLLLLLLL
            1.898902C36UT07408122F1204159ZE184226B<KLLLK10
            For Evaluation Purposes Only
        """.trimIndent()
        val m = best(ocr)
        assertEquals(td3[0], m.lines[0])
        assertEquals("ERIKSSON", m.surname)
        assertEquals("ANNA MARIA", m.givenNames)
        assertEquals("UTO", m.nationality)
        assertTrue(m.allCheckDigitsValid)
        // L (21) and 1 (1) differ by 20, a multiple of 10 whatever the weight: no ICAO check digit
        // can tell them apart. Only the VIZ cross-check can catch this one.
        assertEquals("1898902C3", m.documentNumber)
    }

    @Test fun realNutrientOcrOutputTd1() {
        // Verbatim Nutrient OCR output on the synthetic TD1 card back.
        val ocr = "I<UTOD231458907<<<<<<<<<<<<<<<\n7408122F1204159UTO<KLLLLLLLLH6\nERIKSSONK<ANNA<MARIALLLLLLLLLK"
        val m = best(ocr)
        assertEquals(td1, m.lines)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun realNutrientOcrOutputPhoneLikePhoto() {
        // Verbatim Nutrient OCR output on the blurred, tilted photo: both MRZ lines merged on one OCR
        // line, "P<" read "PK<", and an extra "O" in the nationality that shifts every later field.
        val ocr = "16 APR 07 15 APR 12\n \n" +
            "PK<UTOERIKSSON<<ANNA<MARIA<K<KL<LLLLLLLLLLLKLKLKLKL L898902C36UT0O7408122F1204159ZE184226B<<<<LK10\n" +
            "For Evaluation Purposes Only"
        val m = best(ocr)
        assertEquals(td3, m.lines)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun realOcrLuxembourgIdCard() {
        // Wikimedia Commons specimen, verbatim Nutrient OCR: fillers read as K/L, "<<" read as "<K<K".
        val m = best("IDLUXSPECO73476210715<<<<<<<<K\n8308193F3107151LUX<LKLKLKLLLLL<LK6\nSPECIMEN<K<KJEANNE<K<K<K<K<KLKLLLKLLLLKLKL")
        assertEquals(MrzFormat.TD1, m.format)
        assertEquals("SPECIMEN", m.surname)
        assertEquals("JEANNE", m.givenNames)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun realOcrGermanIdCard2024KFillers() {
        // Licensed Nutrient OCR: "IDD<<" read as "IDD<K<K", and the 4 of the number read as L.
        val m = best("IDD<K<KLZ6311TL75<<<<<<<K<K<K<KK<KKKKL\n8308126<3405019D<<2405<<<<<<K3\nMUSTERMANNS<ERIKA<K<<<<LLLLLKLKKLK")
        assertEquals(MrzFormat.TD1, m.format)
        assertEquals("LZ6311T47", m.documentNumber)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun realOcrGermanIdCardShortStateCode() {
        // Issuing state "D<<" and inflated filler runs; the last check digit is misread by the OCR.
        val m = best("IDD<<T220001293<<<<K<<<LLLLLLKK\n6408125<2010315D<<K<LKLKLKLKLLLLKLKS\nMUSTERMANN<K<KERIKA<KLKLLLLLLKLKLKLKLKL")
        assertEquals(MrzFormat.TD1, m.format)
        assertEquals("T22000129", m.documentNumber)
        assertEquals("MUSTERMANN", m.surname)
        assertEquals("ERIKA", m.givenNames)
        assertEquals("D", m.nationality)
    }

    @Test fun td3WithEmptyOptionalDataIsNotTakenForTd2() {
        val m = best("P<BDRMUSTERMANN<K<KERIKA<CLLLLLL<LLLLLLLLLLLLKLKL\nC1000000<7D<<6408125F1809244<<<<<<<<<<<<<<<8")
        assertEquals(MrzFormat.TD3, m.format)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun realOcrRussianDiplomaticPassport() {
        // Wikimedia Commons specimen, page OCR: "8B" inserted in line 2, fillers read "K<K<KLK…", "08" read "Os8".
        val m = best("MOCKBA/MOSCOW\nPDRUSSMIRNOVA<KKVALENTINA<KLLLLLLLLLLLLLLLLLKLKL\n1100000000RUS8B8008046F0902274<<<<<<K<K<K<KLK<LKLKLKLOs8")
        assertEquals(MrzFormat.TD3, m.format)
        assertEquals("SMIRNOVA", m.surname)
        assertEquals("VALENTINA", m.givenNames)
        assertEquals("RUS", m.nationality)
        assertEquals("800804", m.birthDateRaw)
    }

    @Test fun namesStartingWithFillerLookalikesAreKept() {
        val l1 = "P<UTOSMITH<<KLAUS<LLOYD<<<<<<<<<<<<<<<<<<<<<"
        val m = best(l1 + "\n" + td3[1])
        assertEquals("KLAUS LLOYD", m.givenNames)
    }

    @Test fun documentNumberFixedByCheckDigit() {
        // Number "L898902C3" read as "L8989O2C3": letters are legal there, only the check digit can tell.
        val noisy = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL8989O2C36UTO7408122F1204159ZE184226B<<<<<10"
        val m = best(noisy)
        assertEquals("L898902C3", m.documentNumber)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun linesOfWrongLengthAreFitted() {
        val noisy = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<\nL898902C36UTO7408122F1204159ZE184226B<<<<<<<10"
        val m = best(noisy)
        assertEquals(44, m.lines[0].length)
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun mergedLinesAreSplit() {
        val m = best(td3.joinToString(""))
        assertTrue(m.allCheckDigitsValid)
    }

    @Test fun icaoChecksOnSpecimen() {
        val m = best(td3.joinToString("\n"))
        val checks = IcaoChecks.run(m, today = LocalDate.of(2026, 10, 26))
        assertTrue(checks.none { it.status == CheckStatus.ERROR })
        // The 2012 specimen is expired.
        assertTrue(checks.any { it.label == "Date of expiry" && it.status == CheckStatus.WARNING })
    }

    @Test fun countryCodes() {
        assertTrue(IcaoCountries.isKnown("ARE"))
        assertTrue(IcaoCountries.isKnown("FRA"))
        assertTrue(IcaoCountries.isKnown("D"))
        assertTrue(IcaoCountries.isKnown("XXA"))
        assertTrue(!IcaoCountries.isKnown("QQQ"))
    }

    @Test fun vizCrossCheck() {
        val m = best(td3.joinToString("\n"))
        val viz = """
            PASSPORT Type P Code UTO Passport No. L898902C3
            Surname ERIKSSON Given names ANNA MARIA
            Nationality UTOPIAN Date of birth 12 AUG 74 Sex F
            Date of expiry 15 APR 12
        """.trimIndent()
        val result = VizCrossCheck.run(m, viz).associateBy { it.field }
        assertEquals(VizStatus.FOUND, result.getValue("Surname").status)
        assertEquals(VizStatus.FOUND, result.getValue("Given names").status)
        assertEquals(VizStatus.FOUND, result.getValue("Document number").status)
        assertEquals(VizStatus.FOUND, result.getValue("Date of birth").status)
        assertEquals(VizStatus.FOUND, result.getValue("Issuing state").status)
    }
}
