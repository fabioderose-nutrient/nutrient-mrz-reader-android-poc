package io.nutrient.poc.mrz.mrz

import java.util.Locale

/**
 * ICAO 9303 issuing state / nationality codes: ISO 3166-1 alpha-3 (from the platform locale data,
 * so no list to maintain and nothing downloaded) plus the ICAO-specific codes.
 */
object IcaoCountries {

    private val icaoSpecific = mapOf(
        "D" to listOf("GERMANY", "DEUTSCHLAND"),
        "GBD" to listOf("BRITISH OVERSEAS TERRITORIES CITIZEN"),
        "GBN" to listOf("BRITISH NATIONAL (OVERSEAS)"),
        "GBO" to listOf("BRITISH OVERSEAS CITIZEN"),
        "GBP" to listOf("BRITISH PROTECTED PERSON"),
        "GBS" to listOf("BRITISH SUBJECT"),
        "UNO" to listOf("UNITED NATIONS"),
        "UNA" to listOf("UNITED NATIONS SPECIALIZED AGENCY"),
        "UNK" to listOf("KOSOVO"),
        "RKS" to listOf("KOSOVO"),
        "EUE" to listOf("EUROPEAN UNION"),
        "XBA" to listOf("AFRICAN DEVELOPMENT BANK"),
        "XIM" to listOf("AFRICAN EXPORT-IMPORT BANK"),
        "XCC" to listOf("CARIBBEAN COMMUNITY"),
        "XCE" to listOf("COUNCIL OF EUROPE"),
        "XCO" to listOf("COMMON MARKET FOR EASTERN AND SOUTHERN AFRICA"),
        "XEC" to listOf("ECONOMIC COMMUNITY OF WEST AFRICAN STATES"),
        "XPO" to listOf("INTERPOL"),
        "XES" to listOf("ORGANISATION OF EASTERN CARIBBEAN STATES"),
        "XMP" to listOf("PARLIAMENTARY ASSEMBLY OF THE MEDITERRANEAN"),
        "XOM" to listOf("SOVEREIGN MILITARY ORDER OF MALTA"),
        "XDC" to listOf("SOUTHERN AFRICAN DEVELOPMENT COMMUNITY"),
        "XXA" to listOf("STATELESS"),
        "XXB" to listOf("REFUGEE"),
        "XXC" to listOf("REFUGEE"),
        "XXX" to listOf("UNSPECIFIED NATIONALITY"),
        "UTO" to listOf("UTOPIA"), // ICAO 9303 specimen state
    )

    // Common local spellings missing from locale data.
    private val extraNames = mapOf(
        "ARE" to listOf("UAE", "U.A.E"),
        "GBR" to listOf("BRITISH CITIZEN", "UNITED KINGDOM OF GREAT BRITAIN"),
        "USA" to listOf("UNITED STATES OF AMERICA"),
        "NLD" to listOf("NEDERLAND", "NEDERLANDSE"),
        "FRA" to listOf("REPUBLIQUE FRANCAISE", "FRANCAISE"),
    )

    private val byCode: Map<String, List<String>> by lazy {
        val map = mutableMapOf<String, MutableList<String>>()
        for (alpha2 in Locale.getISOCountries()) {
            val locale = Locale.Builder().setRegion(alpha2).build()
            val alpha3 = runCatching { locale.isO3Country }.getOrNull()?.uppercase() ?: continue
            if (alpha3.length != 3) continue
            val names = listOf(Locale.ENGLISH, Locale.FRENCH)
                .map { VizText.normalize(locale.getDisplayCountry(it)) }
                .filter { it.isNotBlank() && it != alpha2 }
            map.getOrPut(alpha3) { mutableListOf() } += names
        }
        icaoSpecific.forEach { (k, v) -> map.getOrPut(k) { mutableListOf() } += v }
        extraNames.forEach { (k, v) -> map.getOrPut(k) { mutableListOf() } += v.map { VizText.normalize(it) } }
        map.mapValues { it.value.distinct() }
    }

    fun isKnown(code: String): Boolean = byCode.containsKey(code.replace(" ", "").replace("<", ""))

    fun names(code: String): List<String> = byCode[code.replace(" ", "").replace("<", "")].orEmpty()

    fun displayName(code: String): String = names(code).firstOrNull()
        ?.lowercase()?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        ?: "Unknown"
}
