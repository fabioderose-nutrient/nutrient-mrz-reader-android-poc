package io.nutrient.poc.mrz.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nutrient.poc.mrz.MainViewModel
import io.nutrient.poc.mrz.ocr.ImagePrep
import io.nutrient.poc.mrz.pipeline.MrzReadResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** TD3 data page aspect ratio (125 x 88 mm). */
private const val PAGE_RATIO = 125f / 88f

/** Auto-capture state shared between the analyzer thread and the UI. */
private class AutoCapture {
    val enabled = AtomicBoolean(true)
    val triggered = AtomicBoolean(false)
    @Volatile var lastRun = 0L
    @Volatile var lastLines: List<String>? = null
    @Volatile var stable = 0
    @Volatile var frameResult: MrzReadResult? = null

    fun reset() {
        triggered.set(false); stable = 0; lastLines = null; frameResult = null
    }
}

/** Centered crop with the passport page ratio, [fraction] of the frame width. */
private fun guideCrop(src: Bitmap, fraction: Float): Bitmap {
    var w = src.width * fraction
    var h = w / PAGE_RATIO
    if (h > src.height * 0.95f) { h = src.height * 0.95f; w = h * PAGE_RATIO }
    val left = ((src.width - w) / 2).toInt()
    val top = ((src.height - h) / 2).toInt()
    return Bitmap.createBitmap(src, left, top, w.toInt(), h.toInt())
}

@Composable
fun CameraScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    val ctrl = remember { AutoCapture().apply { enabled.set(vm.settings.autoCapture) } }
    var auto by remember { mutableStateOf(vm.settings.autoCapture) }
    var status by remember { mutableStateOf("Place the data page inside the frame") }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown(); captureExecutor.shutdown() }
    }

    fun takePicture(fromAuto: Boolean) {
        status = "Capturing…"
        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val t = SystemClock.elapsedRealtime()
                val bmp = try {
                    ImagePrep.scaleDown(ImagePrep.rotate(image.toBitmap(), image.imageInfo.rotationDegrees), 2400)
                } finally { image.close() }
                val input = if (fromAuto) guideCrop(bmp, 1f) else bmp
                vm.onCaptured(input, SystemClock.elapsedRealtime() - t, ctrl.frameResult)
            }

            override fun onError(exception: ImageCaptureException) {
                status = "Capture failed: ${exception.message}"
                ctrl.reset()
            }
        })
    }

    val analyzer = remember {
        ImageAnalysis.Analyzer { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!ctrl.enabled.get() || ctrl.triggered.get() || now - ctrl.lastRun < 250) return@Analyzer
                ctrl.lastRun = now
                val frame = ImagePrep.rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
                val r = vm.readFrame(guideCrop(frame, 0.98f))
                val mrz = r.mrz
                if (mrz != null && mrz.allCheckDigitsValid) {
                    if (mrz.lines == ctrl.lastLines) ctrl.stable++ else { ctrl.stable = 1; ctrl.lastLines = mrz.lines }
                    status = "Valid MRZ detected (${ctrl.stable}/2), hold still"
                    if (ctrl.stable >= 2 && ctrl.triggered.compareAndSet(false, true)) {
                        ctrl.frameResult = r
                        ContextCompat.getMainExecutor(context).execute { takePicture(fromAuto = true) }
                    }
                } else {
                    ctrl.stable = 0
                    status = if (mrz != null) {
                        "Partial MRZ: ${mrz.validCheckDigitCount}/${mrz.checkDigits.size} check digits (${r.timings.totalMs} ms)"
                    } else "Looking for the MRZ… (${r.timings.totalMs} ms / frame)"
                }
            } catch (t: Throwable) {
                status = "Analysis error: ${t.message}"
            } finally {
                image.close()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar("Scan", onBack = { vm.back() }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Text("Auto", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(end = 8.dp))
                Switch(checked = auto, onCheckedChange = {
                    auto = it; ctrl.enabled.set(it); ctrl.reset(); vm.settings.autoCapture = it
                    status = if (it) "Auto capture on" else "Manual capture: press the button"
                })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder().setResolutionStrategy(
                                        ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                                    ).build()
                                )
                                .build()
                                .also { it.setAnalyzer(analysisExecutor, analyzer) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                        }, ContextCompat.getMainExecutor(ctx))
                        view
                    },
                )
                GuideOverlay()
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access is needed to scan.", color = Color.White)
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 12.dp)) {
                        Text("Allow the camera")
                    }
                }
            }
            Text(
                status,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            FilledIconButton(
                onClick = { ctrl.triggered.set(true); ctrl.frameResult = null; takePicture(fromAuto = false) },
                enabled = granted,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(24.dp).size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Icon(Icons.Default.CameraAlt, "Take the photo", Modifier.size(36.dp)) }
        }
    }
}

/** Passport-shaped frame with the MRZ band highlighted at its bottom. */
@Composable
private fun GuideOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        var w = size.width * 0.9f
        var h = w / PAGE_RATIO
        if (h > size.height * 0.7f) { h = size.height * 0.7f; w = h * PAGE_RATIO }
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
        val guide = Rect(topLeft, Size(w, h))
        val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(guide, CornerRadius(16f))) }
        clipPath(path, clipOp = ClipOp.Difference) { drawRect(Color(0x88000000)) }
        drawRoundRect(Color.White, guide.topLeft, guide.size, CornerRadius(16f), style = Stroke(4f))
        val mrzTop = guide.bottom - h * 0.27f
        drawRoundRect(
            Color(0xFF60A5FA), Offset(guide.left + 12f, mrzTop), Size(w - 24f, guide.bottom - mrzTop - 12f),
            CornerRadius(8f), style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
        )
    }
}
