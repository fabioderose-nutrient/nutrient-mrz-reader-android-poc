# The Nutrient SDK and AndroidX ship their own consumer rules. The OCR runs in a separate process
# and talks to the app through Bundles: keep that package as is.
-keep class io.nutrient.poc.mrz.ocr.** { *; }
