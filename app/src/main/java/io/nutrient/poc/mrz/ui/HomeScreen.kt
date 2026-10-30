package io.nutrient.poc.mrz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.nutrient.poc.mrz.MainViewModel
import io.nutrient.poc.mrz.MrzApp
import io.nutrient.poc.mrz.Screen

@Composable
fun HomeScreen(vm: MainViewModel, app: MrzApp) {
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openImage(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.screen = Screen.BATCH; vm.runBatch(uri) }
    }
    val airGapped = remember { app.isAirGapped() }

    Column(Modifier.fillMaxSize()) {
        TopBar("MRZ Reader · Nutrient PoC", onBack = null) {
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Reads the MRZ of passports and ID cards entirely on the device, with the Nutrient Android SDK OCR " +
                        "and ICAO 9303 rules, then redacts the personal data.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton(Icons.Default.CameraAlt, "Scan with the camera", primary = true) { vm.screen = Screen.CAMERA }
                BigButton(Icons.Default.Image, "Open an image") { pickImage.launch(arrayOf("image/*")) }
                BigButton(Icons.Default.FolderOpen, "Batch mode: analyze a folder") { pickFolder.launch(null) }
                BigButton(Icons.Default.Badge, "Try the built-in specimen") { vm.openSample() }
                Text(
                    "Custom images: open them here, share them to this app from Photos or Files (\"Share\" → MRZ Reader), " +
                        "or run tools/process-image.sh from a computer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                Section("Environment") {
                    KeyValue(
                        "Network",
                        if (airGapped) "No INTERNET permission (air-gapped)" else "INTERNET permission present!",
                        if (airGapped) OkColor else ErrColor,
                    )
                    KeyValue("Nutrient Android SDK", app.nutrientVersion)
                    KeyValue("License", app.licenseMode, if (app.initError != null) WarnColor else Color.Unspecified)
                    app.initError?.let { KeyValue("Key error", it, ErrColor) }
                    KeyValue("Engine", "Nutrient OCR (English) + MRZ rules")
                }
            }
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
    val mod = Modifier.fillMaxWidth().height(60.dp)
    if (primary) Button(onClick, mod, colors = ButtonDefaults.buttonColors()) { content() }
    else OutlinedButton(onClick, mod) { content() }
}
