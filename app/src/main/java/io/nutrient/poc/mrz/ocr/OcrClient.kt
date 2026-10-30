package io.nutrient.poc.mrz.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The OCR process died while reading an image (native crash in the OCR engine). */
class OcrEngineCrashException : RuntimeException("The OCR engine crashed on this image")

/**
 * Blocking client for [OcrService]. Call from a background thread only. If the service process
 * dies mid-request the call fails with [OcrEngineCrashException] and the next call rebinds.
 */
class OcrClient(context: Context) {
    private val app = context.applicationContext
    private val replies = HandlerThread("ocr-client").apply { start() }
    private val workDir = File(app.cacheDir, "ocr").apply { mkdirs() }

    @Volatile private var service: Messenger? = null
    @Volatile private var connected = CountDownLatch(1)
    @Volatile private var pending: Pending? = null

    private class Pending {
        val done = CountDownLatch(1)
        @Volatile var reply: Bundle? = null
        @Volatile var died = false
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            binder.linkToDeath({ onDied() }, 0)
            service = Messenger(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = onDied()
    }

    private fun onDied() {
        service = null
        connected = CountDownLatch(1)
        pending?.let { it.died = true; it.done.countDown() }
    }

    @Synchronized
    fun recognize(bitmap: Bitmap, withBoxes: Boolean = true, withWords: Boolean = false, language: String = "ENGLISH", pointsPerPixel: Float? = null): OcrOutput {
        val start = SystemClock.elapsedRealtime()
        val file = File(workDir, "in_${System.nanoTime()}.jpg")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            val messenger = ensureBound()
            val p = Pending().also { pending = it }
            val msg = Message.obtain(null, 1).apply {
                data = Bundle().apply {
                    putString(OcrService.KEY_PATH, file.absolutePath)
                    putInt(OcrService.KEY_WIDTH, bitmap.width)
                    putInt(OcrService.KEY_HEIGHT, bitmap.height)
                    putBoolean(OcrService.KEY_BOXES, withBoxes)
                    putBoolean(OcrService.KEY_WORDS, withWords)
                    putString(OcrService.KEY_LANGUAGE, language)
                    pointsPerPixel?.let { putFloat(OcrService.KEY_SCALE, it) }
                }
                replyTo = Messenger(Handler(replies.looper) { m -> p.reply = m.data; p.done.countDown(); true })
            }
            messenger.send(msg)
            if (!p.done.await(60, TimeUnit.SECONDS)) error("OCR: timeout")
            pending = null
            if (p.died) {
                // Unbind so the next call starts a fresh OCR process.
                runCatching { app.unbindService(connection) }
                throw OcrEngineCrashException()
            }
            val reply = p.reply ?: error("OCR: empty reply")
            reply.getString(OcrService.KEY_ERROR)?.let { error("OCR: $it") }
            val served = reply.getLong("pdf") + reply.getLong("ocr") + reply.getLong("txt")
            return OcrBundle.read(reply, ipcMs = (SystemClock.elapsedRealtime() - start - served).coerceAtLeast(0))
        } finally {
            file.delete()
        }
    }

    /** Starts the OCR process ahead of the first read (Nutrient init takes ~1 s there). */
    fun warmUp() {
        Thread { runCatching { ensureBound() } }.start()
    }

    private fun ensureBound(): Messenger {
        service?.let { return it }
        app.bindService(Intent(app, OcrService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!connected.await(20, TimeUnit.SECONDS)) error("OCR service unavailable")
        return service ?: error("OCR service unavailable")
    }
}
