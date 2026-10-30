package io.nutrient.poc.mrz.redact

import io.nutrient.poc.mrz.mrz.MrzData
import io.nutrient.poc.mrz.mrz.MrzTextExtractor

/**
 * What the redaction works from: the MRZ and its frame, with fallbacks so the anchor method still
 * works when the first read failed.
 *  - No MRZ from the read pipeline (OCR crash, rotation…): parse it from the page OCR text.
 *  - No parsable MRZ at all (blurred line): build the frame from the MRZ-like lines that are visible,
 *    with the format guessed from their length.
 *  - No usable MRZ words: frame from the MRZ box of the read, without tilt.
 */
object RedactionContext {
    fun resolve(words: List<PageWord>, readMrz: MrzData?, mrzBox: Box?): Pair<MrzData?, MrzFrame?> {
        val mrz = readMrz ?: mrzFromWords(words)
        val format = mrz?.format ?: MrzFrame.guessFormat(words)
        val frame = format?.let { f -> MrzFrame.fromWords(words, f, mrz?.lines) ?: mrzBox?.let { MrzFrame.fromBox(it, f) } }
        return mrz to frame
    }

    private fun mrzFromWords(words: List<PageWord>): MrzData? {
        val text = words.groupBy { it.line }.toSortedMap().values.joinToString("\n") { line -> line.joinToString(" ") { it.text } }
        return MrzTextExtractor.best(text)?.first
    }
}
