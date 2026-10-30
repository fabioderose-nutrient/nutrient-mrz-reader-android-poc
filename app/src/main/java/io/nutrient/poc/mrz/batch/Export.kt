package io.nutrient.poc.mrz.batch

import io.nutrient.poc.mrz.mrz.IcaoChecks
import io.nutrient.poc.mrz.mrz.MrzData
import io.nutrient.poc.mrz.pipeline.MrzReadResult
import org.json.JSONArray
import org.json.JSONObject

/** JSON / CSV exports. Images are never exported, only extracted data and metrics. */
object Export {

    fun resultJson(name: String, r: MrzReadResult): JSONObject = JSONObject().apply {
        put("file", name)
        put("status", r.status.name)
        put("imageWidth", r.source.width)
        put("imageHeight", r.source.height)
        put("timingsMs", JSONObject().apply {
            put("total", r.timings.totalMs)
            put("decode", r.timings.decodeMs)
            put("preprocess", r.timings.prepMs)
            put("ocrProcessHandOff", r.timings.ipcMs)
            put("pdfBuild", r.timings.pdfMs)
            put("ocr", r.timings.ocrMs)
            put("textExtraction", r.timings.textMs)
            put("parse", r.timings.parseMs)
        })
        put("attempts", JSONArray(r.attempts.map {
            JSONObject().put("strategy", it.strategy).put("ms", it.durationMs).put("mrzFound", it.mrzFound)
                .put("validCheckDigits", it.validCheckDigits).put("checkDigits", it.totalCheckDigits)
                .put("error", it.error ?: JSONObject.NULL)
        }))
        r.mrz?.let { put("mrz", mrzJson(it)) }
        r.mrzBox?.let { put("mrzBox", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
    }

    fun mrzJson(m: MrzData): JSONObject = JSONObject().apply {
        put("format", m.format.name)
        put("lines", JSONArray(m.lines))
        put("documentCode", m.documentCode)
        put("issuingState", m.issuingState)
        put("surname", m.surname)
        put("givenNames", m.givenNames)
        put("documentNumber", m.documentNumber)
        put("nationality", m.nationality)
        put("birthDate", m.birthDate?.toString() ?: m.birthDateRaw)
        put("sex", m.sex)
        put("expiryDate", m.expiryDate?.toString() ?: m.expiryDateRaw)
        put("optionalData1", m.optionalData1)
        put("optionalData2", m.optionalData2)
        put("allCheckDigitsValid", m.allCheckDigitsValid)
        put("checkDigits", JSONArray(m.checkDigits.map { JSONObject().put("field", it.field).put("valid", it.valid) }))
        put("checks", JSONArray(IcaoChecks.run(m).map {
            JSONObject().put("group", it.group).put("label", it.label).put("status", it.status.name).put("detail", it.detail)
        }))
        put("corrections", JSONArray(m.corrections.map {
            JSONObject().put("line", it.line + 1).put("position", it.index + 1)
                .put("from", it.from.toString()).put("to", it.to.toString()).put("reason", it.reason)
        }))
    }

    fun batchCsv(rows: List<BatchRow>): String {
        val header = listOf(
            "file", "status", "format", "document_number", "surname", "given_names", "nationality",
            "birth_date", "expiry_date", "valid_check_digits", "total_ms", "ocr_ms", "attempts", "corrections", "error",
        )
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (r in rows) {
            val m = r.mrz
            val cells = listOf(
                r.fileName, r.status.name, m?.format?.name.orEmpty(), m?.documentNumber.orEmpty(), m?.surname.orEmpty(),
                m?.givenNames.orEmpty(), m?.nationality.orEmpty(), m?.birthDate?.toString().orEmpty(),
                m?.expiryDate?.toString().orEmpty(),
                m?.let { "${it.validCheckDigitCount}/${it.checkDigits.size}" }.orEmpty(),
                r.totalMs.toString(), r.ocrMs.toString(), r.attempts.toString(),
                (m?.corrections?.size ?: 0).toString(), r.error.orEmpty(),
            )
            sb.append(cells.joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
