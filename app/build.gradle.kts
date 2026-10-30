plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep the Nutrient artifacts on the same version (required for language packs).
val nutrientVersion = "11.7.0"

// Optional Nutrient license key: the NUTRIENT_LICENSE_KEY environment variable, else
// nutrient.licenseKey=... in the git-ignored local.properties. Empty = evaluation (trial) mode.
val localNutrientKey: String = System.getenv("NUTRIENT_LICENSE_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    ?: rootProject.file("local.properties").takeIf { it.exists() }
        ?.readLines()?.firstOrNull { it.startsWith("nutrient.licenseKey=") }?.substringAfter("=")?.trim().orEmpty()

android {
    namespace = "io.nutrient.poc.mrz"
    compileSdk = 36

    defaultConfig {
        // A Nutrient license key is bound to this application ID.
        applicationId = "io.nutrient.poc.mrz"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "NUTRIENT_LICENSE_KEY", "\"$localNutrientKey\"")

        ndk {
            // Real devices (arm64) + emulators on Apple Silicon (arm64) and Intel (x86_64).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // R8 drops the unused code of Compose, CameraX and the Nutrient SDK (the debug APK is 135 MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // PoC: signed with the debug key so the APK can be sideloaded as-is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libraries: a much smaller APK, extracted once at install.
        jniLibs { useLegacyPackaging = true }
    }
}

// The release APK is for real devices only: no x86_64 (Intel emulator) libraries.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/x86_64/**")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Nutrient: core SDK (PDF processing) + on-device OCR + English trained data.
    implementation("io.nutrient:nutrient:$nutrientVersion")
    implementation("io.nutrient:nutrient-ocr:$nutrientVersion")
    // One OCR model per supported printed-zone language (see OcrLanguages); English also reads the MRZ.
    listOf(
        "english", "french", "german", "spanish", "italian", "dutch", "portuguese", "polish", "turkish",
        "czech", "slovak", "slovenian", "croatian", "serbian", "danish", "norwegian", "swedish", "finnish",
        "indonesian", "malay", "welsh",
    ).forEach { implementation("io.nutrient:nutrient-ocr-$it:$nutrientVersion") }

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val camerax = "1.5.3"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM (Android's is a stub in unit tests), for the audit replay.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
