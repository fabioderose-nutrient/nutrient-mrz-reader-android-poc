package io.nutrient.poc.mrz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nutrient.poc.mrz.batch.BatchRow
import io.nutrient.poc.mrz.batch.Export
import io.nutrient.poc.mrz.mrz.VizCrossCheck
import io.nutrient.poc.mrz.mrz.VizMatch
import io.nutrient.poc.mrz.ocr.ImagePrep
import io.nutrient.poc.mrz.ocr.OcrEngineCrashException
import io.nutrient.poc.mrz.pipeline.MrzPipeline
import io.nutrient.poc.mrz.pipeline.MrzReadResult
import io.nutrient.poc.mrz.redact.BurnResult
import io.nutrient.poc.mrz.redact.MrzFrame
import io.nutrient.poc.mrz.redact.PageWord
import io.nutrient.poc.mrz.redact.RedactField
import io.nutrient.poc.mrz.redact.RedactMethod
import io.nutrient.poc.mrz.redact.RedactionBurner
import io.nutrient.poc.mrz.redact.RedactionPlanner
import io.nutrient.poc.mrz.redact.RedactionRegion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

enum class Screen { HOME, CAMERA, RESULT, REDACT, BATCH, SETTINGS }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app)
    private val pipeline = MrzPipeline(app).apply {
        trialMode = (app as MrzApp).isTrial
        warmUp()
    }

    var screen by mutableStateOf(Screen.HOME)
    var busyLabel by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var result by mutableStateOf<MrzReadResult?>(null)
        private set
    var resultName by mutableStateOf("")
        private set
    private var resultFromBatch = false
    var viz by mutableStateOf<List<VizMatch>?>(null)
        private set
    var vizMs by mutableStateOf(0L)
        private set

    val batchRows = mutableStateListOf<BatchRow>()
    var batchTotal by mutableStateOf(0)
        private set
    var batchRunning by mutableStateOf(false)
        private set
    var batchFolder by mutableStateOf("")
        private set
    private var batchJob: Job? = null

    // Redaction state, for the current result.
    private val burner = RedactionBurner(app)
    var pageWords by mutableStateOf<List<PageWord>?>(null)
        private set
    var pageWordsMs by mutableStateOf(0L)
        private set
    var pageLanguage by mutableStateOf("")
        private set
    var mrzFrame by mutableStateOf<MrzFrame?>(null)
        private set
    /** The MRZ used for redaction: the read's, or one parsed from the page OCR when the read failed. */
    private var redactMrz: io.nutrient.poc.mrz.mrz.MrzData? = null
    var redactMethod by mutableStateOf(RedactMethod.KEYWORDS)
        private set
    var regions by mutableStateOf<List<RedactionRegion>>(emptyList())
        private set
    var disabledFields by mutableStateOf<Set<RedactField>>(emptySet())
        private set
    var burn by mutableStateOf<BurnResult?>(null)
        private set
    /** Set by an external "process this image" request asking to go straight to redaction. */
    private var redactAfterRead = false

    fun back() {
        screen = when (screen) {
            Screen.RESULT -> if (resultFromBatch) Screen.BATCH else Screen.HOME
            Screen.REDACT -> Screen.RESULT
            else -> Screen.HOME
        }
    }

    // --- Single image -------------------------------------------------------------------------

    fun openImage(uri: Uri, name: String = displayName(uri), fromBatch: Boolean = false) = launchRead(name, fromBatch) {
        val t = SystemClock.elapsedRealtime()
        val bitmap = ImagePrep.decode(getApplication(), uri)
        pipeline.read(bitmap, SystemClock.elapsedRealtime() - t, exhaustive = settings.exhaustive)
    }

    /** Image file pushed by tools/process-image.sh (app-specific storage, no permission needed). */
    fun openFile(path: String, redact: Boolean) {
        redactAfterRead = redact
        launchRead(File(path).name, false) {
            val t = SystemClock.elapsedRealtime()
            val bitmap = ImagePrep.decode(getApplication(), Uri.fromFile(File(path)))
            pipeline.read(bitmap, SystemClock.elapsedRealtime() - t, exhaustive = settings.exhaustive)
        }
    }

    fun openSample() = launchRead("ICAO specimen (synthetic)", false) {
        val t = SystemClock.elapsedRealtime()
        val bitmap = getApplication<Application>().assets.open("samples/specimen_td3.jpg").use { BitmapFactory.decodeStream(it) }
        pipeline.read(bitmap, SystemClock.elapsedRealtime() - t, exhaustive = settings.exhaustive)
    }

    /** Photo taken by the camera screen. [frameResult] is the live-frame read that triggered an auto capture. */
    fun onCaptured(bitmap: Bitmap, decodeMs: Long, frameResult: MrzReadResult?) = launchRead("Camera photo", false) {
        val full = pipeline.read(bitmap, decodeMs, exhaustive = settings.exhaustive)
        val frameScore = frameResult?.mrz?.validCheckDigitCount ?: -1
        val fullScore = full.mrz?.validCheckDigitCount ?: -1
        if (frameScore > fullScore) frameResult!! else full
    }

    /** Synchronous read of a live camera frame, called from the CameraX analyzer thread. */
    fun readFrame(bitmap: Bitmap): MrzReadResult = synchronized(pipeline) { pipeline.read(bitmap, fast = true) }

    private fun launchRead(name: String, fromBatch: Boolean, block: () -> MrzReadResult) {
        viewModelScope.launch {
            busyLabel = "Reading the MRZ…"
            try {
                val r = withContext(Dispatchers.Default) { synchronized(pipeline) { block() } }
                result = r
                resultName = name
                resultFromBatch = fromBatch
                viz = null
                pageWords = null
                mrzFrame = null
                redactMrz = null
                regions = emptyList()
                disabledFields = emptySet()
                burn = null
                screen = Screen.RESULT
            } catch (t: Throwable) {
                message = "Failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busyLabel = null
            }
            // After the busy overlay of the read is cleared, so the page analysis shows its own.
            if (redactAfterRead && result != null) { redactAfterRead = false; openRedaction() }
        }
    }

    fun runVizCheck() {
        val r = result ?: return
        val mrz = r.mrz ?: return
        viewModelScope.launch {
            busyLabel = "Full-page OCR…"
            try {
                val (text, ms) = withContext(Dispatchers.Default) { synchronized(pipeline) { pipeline.readFullPageText(r.source, mrz) } }
                viz = VizCrossCheck.run(mrz, text)
                vizMs = ms
            } catch (t: Throwable) {
                message = if (t is OcrEngineCrashException) {
                    "Cross-check unavailable: the OCR engine crashes on this image (known SDK issue). The MRZ result is not affected."
                } else "Cross-check failed: ${t.message}"
            } finally {
                busyLabel = null
            }
        }
    }

    // --- Redaction --------------------------------------------------------------------------------

    /** Opens the redaction screen; the first time, OCRs every word of the upright page. */
    fun openRedaction() {
        val r = result ?: return
        screen = Screen.REDACT
        if (pageWords != null) return
        viewModelScope.launch {
            busyLabel = "Locating every word on the page…"
            try {
                val page = withContext(Dispatchers.Default) { synchronized(pipeline) { pipeline.analyzePage(r).also { pageLanguage = pipeline.lastPageLanguage } } }
                if (page.result !== r) result = page.result
                pageWords = page.words
                pageWordsMs = page.ms
                redactMrz = page.mrz
                mrzFrame = page.frame
                replan()
                android.util.Log.i("MrzRedact", "Page analysis ready: ${page.words.size} words")
            } catch (t: Throwable) {
                message = "Page analysis failed: ${t.message}"
            } finally {
                busyLabel = null
            }
        }
    }

    fun selectMethod(m: RedactMethod) {
        redactMethod = m
        disabledFields = emptySet()
        replan()
    }

    fun toggleField(f: RedactField) {
        disabledFields = if (f in disabledFields) disabledFields - f else disabledFields + f
        burn = null
    }

    private fun replan() {
        val r = result ?: return
        regions = RedactionPlanner.plan(redactMethod, pageWords.orEmpty(), redactMrz ?: r.mrz, mrzFrame, r.source.width.toFloat(), r.source.height.toFloat())
        burn = null
    }

    val activeRegions: List<RedactionRegion> get() = regions.filter { it.field !in disabledFields }

    /** Annotates the page with Nutrient RedactionAnnotations, then applies (burns) them. */
    fun burnRedactions() {
        val r = result ?: return
        val todo = activeRegions
        if (todo.isEmpty()) { message = "Nothing selected to redact"; return }
        viewModelScope.launch {
            busyLabel = "Applying redactions with Nutrient…"
            try {
                burn = withContext(Dispatchers.Default) { burner.burn(r.source, todo) }
                android.util.Log.i("MrzRedact", "Burned ${todo.size} regions")
            } catch (t: Throwable) {
                android.util.Log.w("MrzRedact", "Burn failed", t)
                message = "Redaction failed: ${t.message}"
            } finally {
                busyLabel = null
            }
        }
    }

    fun exportRedactedPdf(target: Uri) = copy(target) { burn?.redactedPdf ?: error("Burn the redactions first") }

    fun exportAnnotatedPdf(target: Uri) = copy(target) { burn?.annotatedPdf ?: error("Burn the redactions first") }

    fun exportRedactedPng(target: Uri) {
        val bmp = burn?.redactedImage ?: run { message = "Burn the redactions first"; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                message = "Export saved"
            } catch (t: Throwable) {
                message = "Export failed: ${t.message}"
            }
        }
    }

    private fun copy(target: Uri, source: () -> File) {
        viewModelScope.launch {
            try {
                val file = source()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                message = "Export saved"
            } catch (t: Throwable) {
                message = "Export failed: ${t.message}"
            }
        }
    }

    // --- Batch ---------------------------------------------------------------------------------

    fun runBatch(tree: Uri) {
        batchJob?.cancel()
        batchRows.clear()
        batchJob = viewModelScope.launch {
            val folder = DocumentFile.fromTreeUri(getApplication(), tree) ?: return@launch
            batchFolder = folder.name.orEmpty()
            val files = folder.listFiles()
                .filter { it.isFile && (it.type?.startsWith("image/") == true) }
                .sortedBy { it.name }
            batchTotal = files.size
            batchRunning = true
            try {
                for (f in files) {
                    if (!isActive) break
                    val row = withContext(Dispatchers.Default) {
                        try {
                            val t = SystemClock.elapsedRealtime()
                            val bmp = ImagePrep.decode(getApplication(), f.uri)
                            val r = synchronized(pipeline) {
                                pipeline.read(bmp, SystemClock.elapsedRealtime() - t, exhaustive = settings.exhaustive)
                            }
                            BatchRow(f.uri, f.name.orEmpty(), r.status, r.mrz, r.timings.totalMs, r.timings.ocrMs, r.attempts.size)
                        } catch (e: Throwable) {
                            BatchRow(f.uri, f.name.orEmpty(), io.nutrient.poc.mrz.pipeline.ReadStatus.NOT_FOUND, null, 0, 0, 0, e.message ?: "erreur")
                        }
                    }
                    batchRows += row
                }
            } finally {
                batchRunning = false
            }
        }
    }

    fun stopBatch() {
        batchJob?.cancel()
    }

    // --- Exports -------------------------------------------------------------------------------

    fun exportResultJson(target: Uri) = write(target) {
        Export.resultJson(resultName, result ?: error("No result")).toString(2)
    }

    fun exportBatchCsv(target: Uri) = write(target) { Export.batchCsv(batchRows.toList()) }

    fun exportBatchJson(target: Uri) = write(target) {
        JSONArray(batchRows.map { row ->
            org.json.JSONObject().put("file", row.fileName).put("status", row.status.name)
                .put("totalMs", row.totalMs).put("ocrMs", row.ocrMs).put("attempts", row.attempts)
                .put("error", row.error ?: org.json.JSONObject.NULL)
                .put("mrz", row.mrz?.let { Export.mrzJson(it) } ?: org.json.JSONObject.NULL)
        }).toString(2)
    }

    private fun write(target: Uri, content: () -> String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target)!!.use {
                        it.write(content().toByteArray())
                    }
                }
                message = "Export saved"
            } catch (t: Throwable) {
                message = "Export failed: ${t.message}"
            }
        }
    }

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(getApplication(), uri)?.name ?: uri.lastPathSegment.orEmpty()
}
