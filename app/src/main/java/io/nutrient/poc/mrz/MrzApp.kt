package io.nutrient.poc.mrz

import android.app.Application
import android.content.pm.PackageManager
import com.pspdfkit.Nutrient
import com.pspdfkit.initialization.InitializationOptions

class MrzApp : Application() {

    /** "License key" or "Trial ..." — shown on the home screen. */
    var licenseMode: String = ""
        private set
    var initError: String? = null
        private set
    var isTrial: Boolean = true
        private set

    override fun onCreate() {
        super.onCreate()
        // Key entered in Settings, else the one built in (NUTRIENT_LICENSE_KEY or local.properties), else trial.
        val key = AppSettings(this).licenseKey.ifBlank { BuildConfig.NUTRIENT_LICENSE_KEY }
        try {
            // An empty key opts into trial mode explicitly; a null key leaves the processor unlicensed.
            Nutrient.initialize(this, InitializationOptions(licenseKey = key))
            isTrial = key.isBlank()
            licenseMode = if (isTrial) "Trial (watermark, stops after 60 min)" else "License key"
        } catch (t: Throwable) {
            // A wrong key must not brick the PoC: fall back to trial mode and tell the user.
            initError = t.message ?: t.javaClass.simpleName
            runCatching { Nutrient.initialize(this, InitializationOptions(licenseKey = "")) }
            licenseMode = "Trial (key rejected)"
        }
        android.util.Log.i("MrzApp", "Nutrient initialized=${Nutrient.isInitialized()} mode=$licenseMode error=$initError features=$licenseFeatures")
    }

    /** True when the merged manifest really has no INTERNET permission. */
    fun isAirGapped(): Boolean {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().none { it == android.Manifest.permission.INTERNET }
    }

    val nutrientVersion: String get() = runCatching { Nutrient.VERSION }.getOrDefault("?")

    /** Components unlocked by the current license (all of them in trial mode). */
    val licenseFeatures: String
        get() = runCatching { Nutrient.getLicenseFeatures().joinToString(", ") { it.name.lowercase() } }.getOrDefault("?")
}
