package io.nutrient.poc.mrz

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.nutrient.poc.mrz.ui.BatchScreen
import io.nutrient.poc.mrz.ui.CameraScreen
import io.nutrient.poc.mrz.ui.HomeScreen
import io.nutrient.poc.mrz.ui.MrzTheme
import io.nutrient.poc.mrz.ui.RedactScreen
import io.nutrient.poc.mrz.ui.ResultScreen
import io.nutrient.poc.mrz.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MrzTheme {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(vm.message) {
                    vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
                }
                BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        when (vm.screen) {
                            Screen.HOME -> HomeScreen(vm, application as MrzApp)
                            Screen.CAMERA -> CameraScreen(vm)
                            Screen.RESULT -> ResultScreen(vm)
                            Screen.REDACT -> RedactScreen(vm)
                            Screen.BATCH -> BatchScreen(vm)
                            Screen.SETTINGS -> SettingsScreen(vm, application as MrzApp)
                        }
                        vm.busyLabel?.let { label ->
                            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White)
                                    Text(label, color = Color.White, modifier = Modifier.padding(top = 16.dp))
                                }
                            }
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Custom images: "Share" or "Open with" from any app (Photos, Files…), or
     * tools/process-image.sh, which pushes a file and starts the app with `--es path <file>`.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("path")?.let { vm.openFile(it, intent.getBooleanExtra("redact", false)); return }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { vm.openImage(it) }
    }
}
