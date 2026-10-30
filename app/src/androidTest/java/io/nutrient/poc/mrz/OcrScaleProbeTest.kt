package io.nutrient.poc.mrz

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nutrient.poc.mrz.ocr.ImagePrep
import io.nutrient.poc.mrz.ocr.OcrClient
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Measures how the PDF page scale (points per image pixel) changes what the Nutrient OCR reads. */
@RunWith(AndroidJUnit4::class)
class OcrScaleProbeTest {
    @Test fun probe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "testset")
        val names = (InstrumentationRegistry.getArguments().getString("files") ?: "specimen_td3.jpg").split(',')
        val client = OcrClient(ctx)
        for (n in names) {
            val src = BitmapFactory.decodeFile(File(dir, n).path)
            for (width in listOf(src.width, 1300, 2000)) {
                val bmp = ImagePrep.scaleUpTo(src, width)
                for (k in listOf(1f)) {
                    val t = System.currentTimeMillis()
                    val out = try {
                        client.recognize(bmp, withBoxes = false, withWords = true, pointsPerPixel = k)
                    } catch (e: Exception) { Log.i("OcrScale", "$n w=${bmp.width} crashed"); continue }
                    Log.i("OcrScale", "$n w=${bmp.width} k=$k ${System.currentTimeMillis() - t} ms words=${out.words.size}: " +
                        out.words.joinToString(" ") { it.text }.take(260))
                }
            }
        }
    }
}
