package io.nutrient.poc.mrz.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.nutrient.poc.mrz.BuildConfig
import io.nutrient.poc.mrz.MainViewModel
import io.nutrient.poc.mrz.MrzApp
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(vm: MainViewModel, app: MrzApp) {
    val s = vm.settings
    val context = LocalContext.current
    var key by remember { mutableStateOf(s.licenseKey) }
    var viewer by remember { mutableStateOf(s.showViewer) }
    var ocrInput by remember { mutableStateOf(s.showOcrInput) }
    var exhaustive by remember { mutableStateOf(s.exhaustive) }
    var auto by remember { mutableStateOf(s.autoCapture) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack = { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("Nutrient license") {
                    Text("Without a key the SDK runs in trial mode. A license key is bound to the application ID below.", style = MaterialTheme.typography.bodySmall)
                    KeyValue("Application ID", BuildConfig.APPLICATION_ID)
                    KeyValue("Current mode", app.licenseMode)
                    KeyValue("License components", app.licenseFeatures)
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, label = { Text("License key") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                    )
                    Button(onClick = {
                        s.licenseKey = key
                        // Nutrient is initialised once per process: restart to apply the key.
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                        context.startActivity(Intent.makeRestartActivityTask(intent.component))
                        exitProcess(0)
                    }) { Text("Save and restart") }
                }
                Section("Display and reading") {
                    Toggle("Show the image (viewer)", "Turn off for a pure performance mode.", viewer) { viewer = it; s.showViewer = it }
                    Toggle("Show the preprocessed image", "Shows exactly what the OCR receives.", ocrInput) { ocrInput = it; s.showOcrInput = it }
                    Toggle("Exhaustive mode", "Tries every strategy even after a valid MRZ is found (slower).", exhaustive) { exhaustive = it; s.exhaustive = it }
                    Toggle("Auto capture by default", "Takes the photo as soon as the same valid MRZ is read twice in a row.", auto) { auto = it; s.autoCapture = it }
                }
                Section("About") {
                    KeyValue("PoC version", BuildConfig.VERSION_NAME)
                    KeyValue("Nutrient Android SDK", app.nutrientVersion)
                    Text(
                        "No data is sent anywhere: the app does not declare the INTERNET permission. " +
                            "Images are not kept; exports only contain the extracted data, or the redacted page.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
