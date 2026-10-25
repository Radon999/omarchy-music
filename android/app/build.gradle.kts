import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Tidal's app keys aren't kept in the repository: tools/tidal-keys.sh writes them to
// tidal.properties from the tidalapi Python package (the one the desktop app uses).
val tidal = Properties().apply {
    rootProject.file("tidal.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// The release key isn't in the repository either: keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) sits next to it, outside, in ~/.local/share/omarchy-music/. Without
// it, release builds are signed with the debug key.
val keystore = Properties().apply {
    File(System.getProperty("user.home"), ".local/share/omarchy-music/keystore.properties")
        .takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "org.omarchy.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.omarchy.music"
        minSdk = 29
        targetSdk = 35
        versionCode = 10105   // major·10000 + minor·100 + patch
        versionName = "1.1.5"
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"${tidal.getProperty("client_id", "")}\"")
        buildConfigField("String", "TIDAL_CLIENT_SECRET", "\"${tidal.getProperty("client_secret", "")}\"")
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) create("release") {
            storeFile = File(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true   // NewPipe's extractor uses newer Java library parts
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val compose = "1.7.0-rc01"
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.animation:animation:$compose")
    implementation("androidx.compose.material3:material3:1.3.0-rc01")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
