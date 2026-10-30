pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Nutrient Android SDK + OCR + language packs.
        maven { url = uri("https://my.nutrient.io/maven/") }
    }
}

rootProject.name = "NutrientMrzPoc"
include(":app")
