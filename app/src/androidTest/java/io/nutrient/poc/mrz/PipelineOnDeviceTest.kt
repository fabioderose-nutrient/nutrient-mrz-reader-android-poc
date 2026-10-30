package io.nutrient.poc.mrz

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nutrient.poc.mrz.mrz.VizCrossCheck
import io.nutrient.poc.mrz.pipeline.MrzPipeline
import io.nutrient.poc.mrz.pipeline.MrzReadResult
import io.nutrient.poc.mrz.pipeline.ReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Nutrient OCR on the synthetic specimens bundled in androidTest/assets.
 * Results are logged under the "MrzTest" tag (adb logcat -s MrzTest).
 */
@RunWith(AndroidJUnit4::class)
class PipelineOnDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val pipeline = MrzPipeline(target).apply { trialMode = (target.applicationContext as MrzApp).isTrial }

    private fun read(name: String): MrzReadResult {
        val bmp = testAssets.open(name).use { BitmapFactory.decodeStream(it) }
        val r = pipeline.read(bmp)
        Log.i("MrzTest", "$name -> ${r.status} in ${r.timings.totalMs} ms (ocr ${r.timings.ocrMs} ms, ${r.attempts.size} attempt(s))")
        r.attempts.forEach { Log.i("MrzTest", "   ${it.strategy}: ${it.durationMs} ms, found=${it.mrzFound}, ${it.validCheckDigits}/${it.totalCheckDigits}") }
        r.mrz?.let { m ->
            m.lines.forEach { Log.i("MrzTest", "   $it") }
            Log.i("MrzTest", "   corrections=${m.corrections.size} box=${r.mrzBox} portrait=${r.portrait?.width}x${r.portrait?.height}")
        } ?: Log.i("MrzTest", "   OCR text: ${r.ocrText.replace('\n', '|')}")
        return r
    }

    @Test fun cleanTd3() {
        val r = read("specimen_td3.jpg")
        assertEquals(ReadStatus.VALID, r.status)
        assertEquals("ERIKSSON", r.mrz!!.surname)
        // Names carry no check digit: the licensed OCR reads "MARIA" as "MARTIA" on this synthetic image.
        assertTrue(r.mrz!!.givenNames.startsWith("ANNA MAR"))
    }

    @Test fun phonePhotoTd3() {
        val r = read("specimen_td3_photo.jpg")
        assertEquals(ReadStatus.VALID, r.status)
    }

    @Test fun td1Back() {
        val r = read("specimen_td1_back.jpg")
        assertEquals(ReadStatus.VALID, r.status)
        assertEquals("D23145890", r.mrz!!.documentNumber)
    }

    @Test fun noMrz() {
        assertNull(read("no_mrz.jpg").mrz)
    }

    @Test fun vizCrossCheck() {
        val bmp = testAssets.open("specimen_td3.jpg").use { BitmapFactory.decodeStream(it) }
        val r = pipeline.read(bmp)
        val (text, ms) = pipeline.readFullPageText(bmp, r.mrz)
        val matches = VizCrossCheck.run(r.mrz!!, text)
        Log.i("MrzTest", "VIZ in $ms ms: " + matches.joinToString { "${it.field}=${it.status}" })
    }
}
