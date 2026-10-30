package io.nutrient.poc.mrz.redact

import io.nutrient.poc.mrz.mrz.MrzFormat
import io.nutrient.poc.mrz.mrz.MrzParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Re-plans the redactions of an audit exported by RedactionAuditTest (device), with the current
 * planner code, on the JVM: OCR stays as captured, frame and regions are recomputed and written to
 * <dir>/replay/. Run with: AUDIT_DIR=audit/audit_nat ./gradlew :app:testDebugUnitTest --tests '*AuditReplayTest*'
 */
class AuditReplayTest {
    private fun box(a: JSONArray) = Box(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
    private fun arr(b: Box) = JSONArray(listOf(b.left, b.top, b.right, b.bottom))

    @Test fun replay() {
        val dir = System.getenv("AUDIT_DIR")?.let { File(it) }
        assumeTrue(dir != null && dir.isDirectory)
        val out = File(dir, "replay").apply { deleteRecursively(); mkdirs() }
        val only = System.getenv("AUDIT_ONLY")
        val trace = StringBuilder()
        RedactionPlanner.debug = if (only != null) { msg -> trace.appendLine(msg) } else null
        for (f in dir!!.listFiles { x -> x.extension == "json" }!!.sortedBy { it.name }.filter { only == null || it.name.contains(only) }) {
            val j = JSONObject(f.readText())
            val words = (0 until j.getJSONArray("words").length()).map { i ->
                val w = j.getJSONArray("words").getJSONObject(i)
                val b = box(w.getJSONArray("b"))
                PageWord(w.getString("t"), w.getInt("l"), b, w.optJSONArray("h")?.let(::box) ?: b, w.optJSONArray("e")?.let(::box) ?: b)
            }
            val mrz = if (j.has("format")) {
                val lines = j.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }
                MrzParser.parse(MrzFormat.valueOf(j.getString("format")), lines)
            } else null
            val (resolved, frame) = RedactionContext.resolve(words, mrz, j.optJSONArray("mrzBox")?.let(::box))
            if (mrz == null && resolved != null) j.put("lines", JSONArray(resolved.lines)).put("format", resolved.format.name).put("status", "FROM_PAGE_OCR")
            j.remove("frame")
            frame?.let { j.put("frame", JSONObject().put("ox", it.origin.x).put("oy", it.origin.y).put("angle", it.angleDeg).put("pxPerMm", it.pxPerMm)) }
            val w = j.optDouble("width", 4000.0).toFloat()
            val h = j.optDouble("height", 4000.0).toFloat()
            for (m in RedactMethod.entries) {
                val regions = RedactionPlanner.plan(m, words, resolved, frame, w, h)
                j.put(m.name, JSONArray(regions.map { r ->
                    JSONObject().put("field", r.field.name).put("source", r.source.name).put("rects", JSONArray(r.rects.map { arr(it) }))
                }))
            }
            File(out, f.name).writeText(j.toString())
            if (only != null) File(out, f.nameWithoutExtension + ".trace.txt").writeText(trace.toString().also { trace.clear() })
            File(dir, f.nameWithoutExtension + ".jpg").takeIf { it.exists() }?.copyTo(File(out, f.nameWithoutExtension + ".jpg"), overwrite = true)
        }
    }
}
