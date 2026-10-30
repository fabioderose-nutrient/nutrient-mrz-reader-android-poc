package io.nutrient.poc.mrz.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nutrient.poc.mrz.MainViewModel
import io.nutrient.poc.mrz.mrz.CheckItem
import io.nutrient.poc.mrz.mrz.CheckStatus
import io.nutrient.poc.mrz.mrz.IcaoChecks
import io.nutrient.poc.mrz.mrz.IcaoCountries
import io.nutrient.poc.mrz.mrz.MrzData
import io.nutrient.poc.mrz.mrz.VizStatus
import io.nutrient.poc.mrz.pipeline.MrzReadResult
import io.nutrient.poc.mrz.pipeline.ReadStatus

@Composable
fun ResultScreen(vm: MainViewModel) {
    val r = vm.result ?: return
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportResultJson(uri)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(vm.resultName, onBack = { vm.back() }) {
            TextButton(onClick = { vm.openRedaction() }) {
                Icon(Icons.Default.HideImage, null, tint = MaterialTheme.colorScheme.onPrimary)
                Text("Redact", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = { exportJson.launch("mrz_${System.currentTimeMillis()}.json") }) {
                Icon(Icons.Default.Save, "Export as JSON", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                // Tablet: image on the left, data on the right.
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ImagePane(vm, r)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailsPane(vm, r)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailsHeader(r)
                    ImagePane(vm, r)
                    DetailsPane(vm, r, withHeader = false)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ImagePane(vm: MainViewModel, r: MrzReadResult) {
    if (vm.settings.showViewer) {
        Section("Image (${r.source.width} × ${r.source.height} px)") {
            ZoomableImage(r.source, r.mrzBox)
            Text("Pinch to zoom, double-tap to reset.", style = MaterialTheme.typography.bodySmall)
        }
    }
    r.portrait?.let {
        Section("Holder photo (TD3 geometric crop)") {
            Image(
                it.asImageBitmap(), "Holder photo",
                Modifier.height(180.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit,
            )
        }
    }
    if (vm.settings.showOcrInput) {
        r.ocrInput?.let {
            Section("Image sent to the OCR (after preprocessing)") {
                Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, box: android.graphics.RectF?) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clipToBounds()
            .transformable(state, canPan = { scale > 1f })
            .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
            Image(image, "Document", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (box != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / bitmap.width
                    drawRect(
                        Color(0xFF22C55E), Offset(box.left * k - 6, box.top * k - 6),
                        Size(box.width() * k + 12, box.height() * k + 12), style = Stroke(4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsHeader(r: MrzReadResult) {
    val (label, color) = when (r.status) {
        ReadStatus.VALID -> "Valid MRZ" to OkColor
        ReadStatus.INVALID_CHECK_DIGITS -> "MRZ read, check digits failed" to WarnColor
        ReadStatus.NOT_FOUND -> "No MRZ found" to ErrColor
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusPill(label, color)
        Text("${r.timings.totalMs} ms", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ColumnScope.DetailsPane(vm: MainViewModel, r: MrzReadResult, withHeader: Boolean = true) {
    if (withHeader) DetailsHeader(r)
    if (r.rotation != 0) Text("The document was found turned by ${r.rotation}°; it is shown upright.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { vm.openRedaction() }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.HideImage, null)
        Text("Redact personal data", Modifier.padding(start = 8.dp))
    }

    Section("Processing time") {
        val t = r.timings
        KeyValue("Total", "${t.totalMs} ms")
        KeyValue("Image decoding", "${t.decodeMs} ms")
        KeyValue("Preprocessing", "${t.prepMs} ms")
        KeyValue("Hand-off to the OCR process", "${t.ipcMs} ms")
        KeyValue("Image → PDF", "${t.pdfMs} ms")
        KeyValue("Nutrient OCR", "${t.ocrMs} ms")
        KeyValue("Text extraction", "${t.textMs} ms")
        KeyValue("MRZ rules", "${t.parseMs} ms")
        Text("Attempts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        r.attempts.forEachIndexed { i, a ->
            val res = a.error ?: if (a.mrzFound) "${a.validCheckDigits}/${a.totalCheckDigits} check digits" else "no MRZ"
            KeyValue("${i + 1}. ${a.strategy}", "${a.durationMs} ms · $res")
        }
    }

    val mrz = r.mrz
    if (mrz == null) {
        Section("Raw OCR text") { OcrText(r.ocrText) }
        return
    }

    Section("Extracted data (${mrz.format.label})") {
        KeyValue("Document type", mrz.documentCode)
        KeyValue("Issuing state", "${mrz.issuingState} · ${IcaoCountries.displayName(mrz.issuingState)}")
        KeyValue("Surname", mrz.surname)
        KeyValue("Given names", mrz.givenNames.ifBlank { "—" })
        KeyValue("Document number", mrz.documentNumber)
        KeyValue("Nationality", "${mrz.nationality} · ${IcaoCountries.displayName(mrz.nationality)}")
        KeyValue("Date of birth", mrz.birthDate?.toString() ?: mrz.birthDateRaw)
        KeyValue("Sex", mrz.sex)
        KeyValue("Date of expiry", mrz.expiryDate?.toString() ?: mrz.expiryDateRaw)
        if (mrz.optionalData1.isNotBlank()) KeyValue("Optional data", mrz.optionalData1)
        if (mrz.optionalData2.isNotBlank()) KeyValue("Optional data 2", mrz.optionalData2)
    }

    Section("MRZ") {
        MrzLines(mrz)
        if (mrz.corrections.isNotEmpty()) {
            Text(
                "${mrz.corrections.size} character(s) corrected by the rules (highlighted):",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            mrz.corrections.forEach {
                Text("line ${it.line + 1}, pos. ${it.index + 1}: '${it.from}' → '${it.to}' (${it.reason})", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    ChecksSection(vm, mrz)

    Section("Raw OCR text") { OcrText(r.ocrText) }
}

@Composable
private fun MrzLines(mrz: MrzData) {
    val corrected = mrz.corrections.map { it.line to it.index }.toSet()
    val text = buildAnnotatedString {
        mrz.lines.forEachIndexed { li, line ->
            line.forEachIndexed { ci, c ->
                if ((li to ci) in corrected) withStyle(SpanStyle(background = Color(0xFFFDE68A), fontWeight = FontWeight.Bold)) { append(c) }
                else append(c)
            }
            if (li < mrz.lines.lastIndex) append('\n')
        }
    }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.background(Color(0xFFF3F4F6), RoundedCornerShape(6.dp)).padding(8.dp),
            color = Color.Black,
        )
    }
}

@Composable
private fun ChecksSection(vm: MainViewModel, mrz: MrzData) {
    var showCheckDigits by remember(mrz) { mutableStateOf(false) }
    var showDates by remember(mrz) { mutableStateOf(false) }
    var showStructure by remember(mrz) { mutableStateOf(false) }

    Section("Consistency checks") {
        Text(
            "These checks catch an impossible or inconsistent MRZ. They do not replace an authenticity check.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { showCheckDigits = !showCheckDigits }) { Text("Check the MRZ") }
            OutlinedButton(onClick = { showDates = !showDates }) { Text("Check the dates") }
            OutlinedButton(onClick = { showStructure = !showStructure }) { Text("Structure and codes") }
            OutlinedButton(onClick = { vm.runVizCheck() }) { Text("Cross-check printed zone ↔ MRZ") }
        }
        if (showCheckDigits) CheckList("Check digits ICAO 9303", IcaoChecks.checkDigits(mrz))
        if (showDates) CheckList("Dates", IcaoChecks.dates(mrz))
        if (showStructure) CheckList("Structure and country codes", IcaoChecks.structure(mrz) + IcaoChecks.codes(mrz))

        vm.viz?.let { matches ->
            Text("Printed zone ↔ MRZ (full-page OCR: ${vm.vizMs} ms)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            matches.forEach { m ->
                val color = when (m.status) { VizStatus.FOUND -> OkColor; VizStatus.PARTIAL -> WarnColor; VizStatus.NOT_FOUND -> ErrColor }
                val label = when (m.status) { VizStatus.FOUND -> "found"; VizStatus.PARTIAL -> "partial"; VizStatus.NOT_FOUND -> "not found" }
                CheckRow(m.field, "$label · ${m.mrzValue} · ${m.evidence}", color)
            }
        }
    }
}

@Composable
private fun CheckList(title: String, items: List<CheckItem>) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    items.forEach { CheckRow(it.label, it.detail, it.status.color()) }
}

@Composable
private fun CheckRow(label: String, detail: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).width(10.dp).height(10.dp).background(color, RoundedCornerShape(50)))
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OcrText(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show (${text.length} characters)") }
    if (expanded) {
        Text(text.ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
