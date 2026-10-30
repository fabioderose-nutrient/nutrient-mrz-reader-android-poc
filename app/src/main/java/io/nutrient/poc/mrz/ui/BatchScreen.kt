package io.nutrient.poc.mrz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nutrient.poc.mrz.MainViewModel
import io.nutrient.poc.mrz.batch.BatchRow
import io.nutrient.poc.mrz.batch.BatchStats
import io.nutrient.poc.mrz.pipeline.ReadStatus

@Composable
fun BatchScreen(vm: MainViewModel) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.runBatch(uri)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportBatchCsv(uri)
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBatchJson(uri)
    }
    val rows = vm.batchRows.toList()
    val stats = BatchStats.of(rows)

    Column(Modifier.fillMaxSize()) {
        TopBar("Batch mode${if (vm.batchFolder.isNotBlank()) " · ${vm.batchFolder}" else ""}", onBack = { vm.back() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.batchRunning) {
                Text("Analyzing ${rows.size} / ${vm.batchTotal}…")
                LinearProgressIndicator(
                    progress = { if (vm.batchTotal == 0) 0f else rows.size.toFloat() / vm.batchTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { vm.stopBatch() }) { Text("Stop") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFolder.launch(null) }) { Text("Choose a folder") }
                    OutlinedButton(onClick = { exportCsv.launch("mrz_batch.csv") }, enabled = rows.isNotEmpty()) { Text("CSV") }
                    OutlinedButton(onClick = { exportJson.launch("mrz_batch.json") }, enabled = rows.isNotEmpty()) { Text("JSON") }
                }
            }
            if (rows.isNotEmpty()) {
                Section("Results") {
                    KeyValue("Images processed", "${stats.count}")
                    KeyValue("Valid MRZ (all check digits)", "${stats.valid} · ${"%.1f".format(stats.validRate)} %", OkColor)
                    KeyValue("MRZ found", "${stats.mrzFound} · ${"%.1f".format(stats.foundRate)} %")
                    KeyValue("Average time", "${stats.avgMs} ms")
                    KeyValue("Median / p95", "${stats.medianMs} ms / ${stats.p95Ms} ms")
                    KeyValue("Min / max", "${stats.minMs} ms / ${stats.maxMs} ms")
                }
            } else if (!vm.batchRunning) {
                Text("Choose a folder of images (JPEG, PNG…). Images stay on the device; only the results can be exported.")
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.uri.toString() }) { row -> BatchRowItem(row) { vm.openImage(row.uri, row.fileName, fromBatch = true) } }
        }
    }
}

@Composable
private fun BatchRowItem(row: BatchRow, onClick: () -> Unit) {
    val color = when {
        row.error != null -> ErrColor
        row.status == ReadStatus.VALID -> OkColor
        row.status == ReadStatus.INVALID_CHECK_DIGITS -> WarnColor
        else -> ErrColor
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(10.dp).height(10.dp).background(color, RoundedCornerShape(50)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(row.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val detail = row.error ?: row.mrz?.let {
                "${it.format.name} · ${it.documentNumber} · ${it.surname} · ${it.validCheckDigitCount}/${it.checkDigits.size} check digits"
            } ?: "no MRZ"
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${row.totalMs} ms", style = MaterialTheme.typography.bodyMedium)
    }
}
