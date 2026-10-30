package io.nutrient.poc.mrz

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nutrient.poc.mrz.pipeline.MrzPipeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Benchmarks every image pushed to the app's external files dir, e.g.:
 *   adb push testset/. /sdcard/Android/data/io.nutrient.poc.mrz/files/testset/
 * Skipped when the folder is absent. Results: adb logcat -s MrzBench
 */
@RunWith(AndroidJUnit4::class)
class TestsetOnDeviceTest {
    @Test fun benchmark() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "testset")
        val only = InstrumentationRegistry.getArguments().getString("only")
        val files = dir.listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.filter { only == null || only.split(',').any { o -> it.name.contains(o) } }?.sortedBy { it.name }
        assumeTrue("no test set in $dir", !files.isNullOrEmpty())

        val pipeline = MrzPipeline(ctx).apply { trialMode = (ctx.applicationContext as MrzApp).isTrial }
        // The logcat ring buffer drops lines on a long run: the summary also goes to files/bench.tsv.
        val tsv = File(ctx.getExternalFilesDir(null), "bench.tsv").apply { writeText("") }
        for (f in files!!) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val r = pipeline.read(bmp)
            val m = r.mrz
            Log.i(
                "MrzBench",
                "${f.name}\t${r.status}\t${r.timings.totalMs} ms\t${m?.format ?: "-"}\t${m?.documentNumber ?: "-"}\t" +
                    "${m?.surname ?: "-"}\t${m?.givenNames ?: "-"}\t${m?.let { "${it.validCheckDigitCount}/${it.checkDigits.size}" } ?: "-"}\t" +
                    "corr=${m?.corrections?.size ?: 0}\t${r.attempts.size} tent.",
            )
            tsv.appendText(
                "${f.name}\t${r.status}\t${r.timings.totalMs}\t${m?.format ?: "-"}\t${m?.documentNumber ?: "-"}\t" +
                    "${m?.surname ?: "-"}\t${m?.givenNames ?: "-"}\t${m?.let { "${it.validCheckDigitCount}/${it.checkDigits.size}" } ?: "-"}\t" +
                    "${r.attempts.size}\t${r.attempts.count { it.error != null }}\n",
            )
            m?.lines?.forEach { Log.i("MrzBench", "    $it") }
            r.attempts.forEach { Log.i("MrzBench", "    · ${it.strategy}: ${it.durationMs} ms, ${it.validCheckDigits}/${it.totalCheckDigits}${it.error?.let { e -> " ($e)" } ?: ""}") }
            if (r.status != io.nutrient.poc.mrz.pipeline.ReadStatus.VALID) {
                Log.i("MrzBench", "    OCR: " + r.ocrText.lines().filter { it.length > 20 }.joinToString(" | "))
            }
        }
    }
}
