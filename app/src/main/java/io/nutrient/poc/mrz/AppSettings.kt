package io.nutrient.poc.mrz

import android.content.Context

/** Local preferences only. The license key never leaves the device. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mrz_poc", Context.MODE_PRIVATE)

    var licenseKey: String
        get() = prefs.getString("license_key", "").orEmpty()
        set(v) = prefs.edit().putString("license_key", v.trim()).apply()

    var showViewer: Boolean
        get() = prefs.getBoolean("show_viewer", true)
        set(v) = prefs.edit().putBoolean("show_viewer", v).apply()

    var showOcrInput: Boolean
        get() = prefs.getBoolean("show_ocr_input", false)
        set(v) = prefs.edit().putBoolean("show_ocr_input", v).apply()

    var exhaustive: Boolean
        get() = prefs.getBoolean("exhaustive", false)
        set(v) = prefs.edit().putBoolean("exhaustive", v).apply()

    var autoCapture: Boolean
        get() = prefs.getBoolean("auto_capture", true)
        set(v) = prefs.edit().putBoolean("auto_capture", v).apply()
}
