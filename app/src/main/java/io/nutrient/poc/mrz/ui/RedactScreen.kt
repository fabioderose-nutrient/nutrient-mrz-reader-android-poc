package io.nutrient.poc.mrz.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pspdfkit.ui.PdfActivityIntentBuilder
import io.nutrient.poc.mrz.MainViewModel
import io.nutrient.poc.mrz.redact.RedactField
import io.nutrient.poc.mrz.redact.RedactMethod
import io.nutrient.poc.mrz.redact.RedactionRegion

/** One colour per field, so the preview shows which rule produced which rectangle. */
private fun RedactField.color(): Color = when (this) {
    RedactField.PHOTO -> Color(0xFF7C3AED)
    RedactField.MRZ -> Color(0xFF0F766E)
    RedactField.PERSONAL_DATA, RedactField.BACK_DATA -> Color(0xFFDC2626)
    RedactField.HEADER -> Color(0xFFEA580C)
    RedactField.SURNAME, RedactField.GIVEN_NAMES -> Color(0xFFDC2626)
    RedactField.DOCUMENT_NUMBER, RedactField.PERSONAL_NUMBER -> Color(0xFFEA580C)
    RedactField.DATE_OF_BIRTH, RedactField.DATE_OF_ISSUE, RedactField.DATE_OF_EXPIRY -> Color(0xFF2563EB)
    else -> Color(0xFFDB2777)
}

@Composable
fun RedactScreen(vm: MainViewModel) {
    val r = vm.result ?: return
    val context = LocalContext.current
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::exportRedactedPdf) }
    val exportAnnotated = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::exportAnnotatedPdf) }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let(vm::exportRedactedPng) }
    var showRedacted by remember(vm.burn) { mutableStateOf(vm.burn != null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Redact · ${vm.resultName}", onBack = { vm.back() })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val preview: @Composable ColumnScope.() -> Unit = {
                val burned = vm.burn
                if (burned != null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!showRedacted, { showRedacted = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Annotated") }
                        SegmentedButton(showRedacted, { showRedacted = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Redacted (burned)") }
                    }
                }
                if (burned != null && showRedacted) {
                    Image(burned.redactedImage.asImageBitmap(), "Redacted page", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    Text(
                        "Rendered from the PDF produced by Nutrient applyRedactions(): the covered pixels are gone, not hidden.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    AnnotatedPreview(r.source, vm.activeRegions)
                }
            }
            val controls: @Composable ColumnScope.() -> Unit = {
                Section("Method") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RedactMethod.entries.forEachIndexed { i, m ->
                            SegmentedButton(vm.redactMethod == m, { vm.selectMethod(m) }, SegmentedButtonDefaults.itemShape(i, RedactMethod.entries.size)) { Text(m.label) }
                        }
                    }
                    Text(
                        when (vm.redactMethod) {
                            RedactMethod.KEYWORDS -> "Field by field. Values read in the MRZ (names, number, dates, nationality) are searched in the " +
                                "printed text; other fields (place of birth, date of issue, authority, sex) are taken under their printed label, " +
                                "in the label's column. The photo position comes from the MRZ."
                            RedactMethod.MRZ_ANCHOR -> "Zone by zone. The MRZ (OCR-B, 2.54 mm per character) gives the scale, position and tilt of the " +
                                "page; the ICAO 9303 zones (photo, personal data, header) are placed relative to it, so they follow a tilted photo."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val f = vm.mrzFrame
                    KeyValue("Page OCR (every word)", "${vm.pageWordsMs} ms · ${vm.pageWords?.size ?: 0} words")
                    KeyValue("OCR language", vm.pageLanguage.lowercase().replaceFirstChar { it.uppercase() } + " (from the MRZ issuing state)")
                    KeyValue(
                        "MRZ frame",
                        f?.let { "tilt ${"%.1f".format(it.angleDeg)}° · ${"%.1f".format(it.pxPerMm)} px/mm · ${it.format.name}" }
                            ?: "no MRZ: anchor unavailable",
                        if (f == null) WarnColor else Color.Unspecified,
                    )
                }
                Section("Fields to redact (${vm.activeRegions.size}/${vm.regions.size})") {
                    if (vm.regions.isEmpty()) Text("Nothing found to redact with this method.", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.regions.forEach { region ->
                            val on = region.field !in vm.disabledFields
                            FilterChip(
                                selected = on,
                                onClick = { vm.toggleField(region.field) },
                                label = { Text(region.field.label) },
                                leadingIcon = if (on) ({ Icon(Icons.Default.Check, null, tint = region.field.color()) }) else null,
                            )
                        }
                    }
                    vm.regions.forEach { region ->
                        Text(
                            "${region.field.label}: ${region.source.label}, ${region.rects.size} rect(s)",
                            style = MaterialTheme.typography.bodySmall, color = region.field.color(),
                        )
                    }
                }
                Button(onClick = { vm.burnRedactions() }, modifier = Modifier.fillMaxWidth(), enabled = vm.activeRegions.isNotEmpty()) {
                    Text("Burn redactions with Nutrient")
                }
                vm.burn?.let { b ->
                    Section("Redacted") {
                        KeyValue("Annotate (RedactionAnnotation)", "${b.annotateMs} ms")
                        KeyValue("Burn (applyRedactions) + render", "${b.burnMs} ms")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                context.startActivity(PdfActivityIntentBuilder.fromUri(context, Uri.fromFile(b.annotatedPdf)).build())
                            }) { Text("Review in Nutrient viewer") }
                            OutlinedButton(onClick = { exportPdf.launch("redacted_${System.currentTimeMillis()}.pdf") }) { Text("Redacted PDF") }
                            OutlinedButton(onClick = { exportPng.launch("redacted_${System.currentTimeMillis()}.png") }) { Text("Redacted PNG") }
                            OutlinedButton(onClick = { exportAnnotated.launch("annotated_${System.currentTimeMillis()}.pdf") }) { Text("Annotated PDF") }
                        }
                        Text(
                            "The viewer opens the annotated PDF: redactions can be moved, resized, added or applied there with the Nutrient redaction tools.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = preview)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = controls)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    preview()
                    controls()
                }
            }
        }
    }
}

/** The page with every planned redaction drawn as a translucent, labelled rectangle (not applied yet). */
@Composable
private fun AnnotatedPreview(bitmap: Bitmap, regions: List<RedactionRegion>) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(image, "Page", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / bitmap.width
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = 11.dp.toPx()
                typeface = Typeface.DEFAULT_BOLD
            }
            for (region in regions) {
                val c = region.field.color()
                for (rect in region.rects) {
                    val topLeft = Offset(rect.left * k, rect.top * k)
                    val sz = Size(rect.width * k, rect.height * k)
                    drawRect(c.copy(alpha = 0.35f), topLeft, sz)
                    drawRect(c, topLeft, sz, style = Stroke(1.5.dp.toPx()))
                }
                val first = region.rects.minBy { it.top }
                paint.color = c.toArgb()
                drawContext.canvas.nativeCanvas.drawText(region.field.label, first.left * k + 2, first.top * k - 3, paint)
            }
        }
    }
}
