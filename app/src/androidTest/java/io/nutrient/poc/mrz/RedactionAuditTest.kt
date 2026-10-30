package io.nutrient.poc.mrz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nutrient.poc.mrz.redact.Box
import io.nutrient.poc.mrz.redact.MrzFrame
import io.nutrient.poc.mrz.redact.RedactMethod
import io.nutrient.poc.mrz.redact.RedactionPlanner
import io.nutrient.poc.mrz.pipeline.MrzPipeline
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Audit run: for every image in <external files>/testset, writes the upright page and a JSON with
 * the MRZ, the OCR words, the MRZ frame and the regions of both redaction methods to
 * <external files>/audit, so they can be drawn and checked image by image on a computer.
 */
@RunWith(AndroidJUnit4::class)
class RedactionAuditTest {
    private fun box(b: Box) = JSONArray(listOf(b.left, b.top, b.right, b.bottom))

    @Test fun audit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val inDir = File(ctx.getExternalFilesDir(null), "testset")
        val outDir = File(ctx.getExternalFilesDir(null), InstrumentationRegistry.getArguments().getString("out") ?: "audit")
            .apply { deleteRecursively(); mkdirs() }
        val only = InstrumentationRegistry.getArguments().getString("only")
        val files = inDir.listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.filter { only == null || it.name.contains(only) }?.sortedBy { it.name }
        assumeTrue(!files.isNullOrEmpty())
        val pipeline = MrzPipeline(ctx).apply {
            trialMode = (ctx.applicationContext as MrzApp).isTrial
            forcedPageLanguage = InstrumentationRegistry.getArguments().getString("lang")
        }

        for (f in files!!) try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val page = pipeline.analyzePage(pipeline.read(bmp))
            val r = page.result
            val words = page.words
            val mrz = page.mrz
            val frame = page.frame
            val wordsMs = page.ms
            val base = f.nameWithoutExtension
            File(outDir, "$base.jpg").outputStream().use { r.source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val json = JSONObject().apply {
                put("file", f.name)
                put("status", r.status.name)
                put("rotation", r.rotation)
                put("ms", r.timings.totalMs)
                put("wordsMs", wordsMs)
                put("ocrLanguage", pipeline.lastPageLanguage)
                mrz?.let { m ->
                    put("format", m.format.name)
                    put("lines", JSONArray(m.lines))
                    put("checks", "${m.validCheckDigitCount}/${m.checkDigits.size}")
                    put("fields", JSONObject().put("surname", m.surname).put("given", m.givenNames).put("number", m.documentNumber)
                        .put("nationality", m.nationality).put("state", m.issuingState).put("birth", m.birthDateRaw).put("expiry", m.expiryDateRaw).put("sex", m.sex))
                }
                r.mrzBox?.let { put("mrzBox", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
                frame?.let { put("frame", JSONObject().put("ox", it.origin.x).put("oy", it.origin.y).put("angle", it.angleDeg).put("pxPerMm", it.pxPerMm)) }
                put("words", JSONArray(words.map { JSONObject().put("t", it.text).put("l", it.line).put("b", box(it.box)).put("h", box(it.head)).put("e", box(it.tail)) }))
                put("width", r.source.width)
                put("height", r.source.height)
                for (m in RedactMethod.entries) {
                    val regions = RedactionPlanner.plan(m, words, mrz, frame, r.source.width.toFloat(), r.source.height.toFloat())
                    put(m.name, JSONArray(regions.map { reg ->
                        JSONObject().put("field", reg.field.name).put("source", reg.source.name).put("rects", JSONArray(reg.rects.map { box(it) }))
                    }))
                }
            }
            File(outDir, "$base.json").writeText(json.toString())
        } catch (t: Throwable) {
            File(outDir, "${f.nameWithoutExtension}.error.txt").writeText(t.stackTraceToString())
        }
    }
}
