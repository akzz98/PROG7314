plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// AGP 9 compiles Kotlin itself and does not use org.jetbrains.kotlin.android.
// This classpath pins the Kotlin Gradle plugin to the same version as the Compose
// compiler plugin in gradle/libs.versions.toml.
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}
