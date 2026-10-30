package io.nutrient.poc.mrz.mrz

/**
 * Language of the printed (visual) zone, chosen from the issuing state read in the MRZ. The MRZ is
 * always read with the English model (A-Z, 0-9, '<'); the page OCR then uses the national model.
 *
 * Values are the names of the Nutrient Android `OcrLanguage` packs (21 Latin-script languages).
 * There is no Arabic, Cyrillic or CJK pack: those documents fall back to English, which still reads
 * their Latin-script fields (most data pages repeat labels and names in English).
 */
object OcrLanguages {
    const val DEFAULT = "ENGLISH"

    private val byCountry: Map<String, String> = buildMap {
        fun lang(name: String, vararg codes: String) = codes.forEach { code -> this[code] = name }
        lang("GERMAN", "D", "DEU", "AUT", "LIE")
        lang("FRENCH", "FRA", "BEL", "LUX", "MCO", "CHE", "SEN", "CIV", "CMR", "MLI", "BFA", "NER", "TCD", "GAB", "COG", "COD", "BEN", "TGO", "GIN", "MDG", "HTI", "MAR", "DZA", "TUN")
        lang("DUTCH", "NLD", "SUR")
        lang("SPANISH", "ESP", "MEX", "ARG", "COL", "CHL", "PER", "VEN", "ECU", "GTM", "CUB", "BOL", "DOM", "HND", "PRY", "SLV", "NIC", "CRI", "PAN", "URY", "GNQ")
        lang("ITALIAN", "ITA", "SMR", "VAT")
        lang("PORTUGUESE", "PRT", "BRA", "AGO", "MOZ", "CPV", "GNB", "STP", "TLS")
        lang("POLISH", "POL")
        lang("TURKISH", "TUR")
        lang("CZECH", "CZE")
        lang("SLOVAK", "SVK")
        lang("SLOVENIAN", "SVN")
        lang("CROATIAN", "HRV", "BIH")
        lang("SERBIAN", "SRB", "MNE")
        lang("DANISH", "DNK")
        lang("NORWEGIAN", "NOR")
        lang("SWEDISH", "SWE")
        lang("FINNISH", "FIN")
        lang("INDONESIAN", "IDN")
        lang("MALAY", "MYS", "BRN")
        lang("WELSH")
    }

    fun forCountry(code: String?): String = code?.replace(" ", "")?.let { byCountry[it] } ?: DEFAULT
}
