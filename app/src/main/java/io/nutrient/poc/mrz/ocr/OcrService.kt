package io.nutrient.poc.mrz.ocr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File

/**
 * Hosts the Nutrient OCR in its own process (":ocr", see the manifest). Nutrient 11.7.0 crashes
 * natively in its Tesseract bridge on some images; isolated here, such a crash only fails one OCR
 * attempt instead of killing the app or a batch run. Nutrient is initialised by MrzApp, which
 * Android creates in this process too.
 */
class OcrService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("nutrient-ocr").apply { start() }
        val engine = NutrientOcrEngine(this)
        messenger = Messenger(Handler(thread.looper) { msg -> handle(engine, msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(engine: NutrientOcrEngine, msg: Message) {
        val reply = Message.obtain(null, msg.what)
        val data = msg.data
        reply.data = try {
            val out = engine.recognize(
                File(data.getString(KEY_PATH)!!), data.getInt(KEY_WIDTH), data.getInt(KEY_HEIGHT), data.getBoolean(KEY_BOXES),
                data.getBoolean(KEY_WORDS),
                runCatching { com.pspdfkit.document.processor.ocr.OcrLanguage.valueOf(data.getString(KEY_LANGUAGE)!!) }
                    .getOrDefault(com.pspdfkit.document.processor.ocr.OcrLanguage.ENGLISH),
                data.getFloat(KEY_SCALE, NutrientOcrEngine.POINTS_PER_PIXEL),
            )
            OcrBundle.write(out)
        } catch (t: Throwable) {
            Bundle().apply { putString(KEY_ERROR, t.message ?: t.javaClass.simpleName) }
        }
        runCatching { msg.replyTo.send(reply) }
    }

    companion object {
        const val KEY_PATH = "path"
        const val KEY_WIDTH = "w"
        const val KEY_HEIGHT = "h"
        const val KEY_BOXES = "boxes"
        const val KEY_WORDS = "words"
        const val KEY_LANGUAGE = "language"
        const val KEY_SCALE = "pointsPerPixel"
        const val KEY_ERROR = "error"
    }
}

/** OcrOutput <-> Bundle for the Messenger hop. */
internal object OcrBundle {
    fun write(o: OcrOutput) = Bundle().apply {
        putString("text", o.text)
        putStringArray("lines", o.lines.map { it.text }.toTypedArray())
        putFloatArray("boxes", o.lines.flatMap { l ->
            l.box?.let { listOf(it.left, it.top, it.right, it.bottom) } ?: listOf(Float.NaN, 0f, 0f, 0f)
        }.toFloatArray())
        putStringArray("wordTexts", o.words.map { it.text }.toTypedArray())
        putIntArray("wordLines", o.words.map { it.line }.toIntArray())
        putFloatArray("wordBoxes", o.words.flatMap { w ->
            listOf(w.box, w.head, w.tail).flatMap { listOf(it.left, it.top, it.right, it.bottom) }
        }.toFloatArray())
        putLong("pdf", o.pdfBuildMs)
        putLong("ocr", o.ocrMs)
        putLong("txt", o.textMs)
    }

    fun read(b: Bundle, ipcMs: Long): OcrOutput {
        val texts = b.getStringArray("lines").orEmpty()
        val boxes = b.getFloatArray("boxes") ?: FloatArray(0)
        val lines = texts.mapIndexed { i, t ->
            val box = if (boxes.size >= i * 4 + 4 && !boxes[i * 4].isNaN()) {
                android.graphics.RectF(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3])
            } else null
            OcrLine(t, box)
        }
        val wt = b.getStringArray("wordTexts").orEmpty()
        val wl = b.getIntArray("wordLines") ?: IntArray(0)
        val wb = b.getFloatArray("wordBoxes") ?: FloatArray(0)
        fun rect(i: Int) = android.graphics.RectF(wb[i], wb[i + 1], wb[i + 2], wb[i + 3])
        val words = wt.indices.map { i -> OcrWord(wt[i], wl[i], rect(i * 12), rect(i * 12 + 4), rect(i * 12 + 8)) }
        return OcrOutput(b.getString("text").orEmpty(), lines, b.getLong("pdf"), b.getLong("ocr"), b.getLong("txt"), ipcMs, words)
    }
}
