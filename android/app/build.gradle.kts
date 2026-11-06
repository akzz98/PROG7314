import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

android {
    namespace = "za.co.munipulse"
    compileSdk = 37

    val local = readLocalProperties()
    signingConfigs {
        create("release") {
            val store = local.getProperty("RELEASE_STORE_FILE")?.trim().orEmpty()
            if (store.isNotEmpty()) {
                storeFile = rootProject.file(store)
                storePassword = local.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = local.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = local.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        // Must match the Android package registered in google-services.json.
        applicationId = "com.munipulse"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl(local, release = false)}\"")
        }
        release {
            // Minify stays off. Gson and Retrofit use model field names, and there is no keep file yet.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl(local, release = true)}\"")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.retrofit.gson)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// API host and the release keystore are local configuration, not committed secrets.
private fun readLocalProperties(): Properties {
    val properties = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    return properties
}

private fun apiBaseUrl(properties: Properties, release: Boolean): String {
    val configured = properties.getProperty("API_BASE_URL")?.trim().orEmpty()
    if (release) {
        val https = if (configured.startsWith("https://")) configured else "https://replace-me.invalid/"
        return https.trimEnd('/') + "/"
    }
    val base = if (configured.isEmpty()) "http://10.0.2.2:5285/" else configured
    return base.trimEnd('/') + "/"
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        doFirst {
            val configured = readLocalProperties().getProperty("API_BASE_URL")?.trim().orEmpty()
            if (!configured.startsWith("https://")) {
                throw GradleException(
                    "Release builds need API_BASE_URL to start with https:// in android/local.properties.",
                )
            }
        }
    }
}
