import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: reads keystore.properties (local builds) or environment variables (GitHub Actions).
// If neither is present the release build falls back to the debug key so it still builds.
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    (keyProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val ksFile = signingValue("storeFile", "FARLOH_KEYSTORE_FILE")
val ksPass = signingValue("storePassword", "FARLOH_KEYSTORE_PASSWORD")
val ksAlias = signingValue("keyAlias", "FARLOH_KEY_ALIAS") ?: "farloh"
val keyPass = signingValue("keyPassword", "FARLOH_KEY_PASSWORD") ?: ksPass
val hasReleaseKey = ksFile != null && ksPass != null && rootProject.file(ksFile).exists()

android {
    namespace = "com.farloh.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.farloh.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(ksFile!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

base {
    archivesName.set("FarlohTV")
}

dependencies {
    val media3 = "1.5.0"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg software audio decoders (AC3, E-AC3, DTS, TrueHD...) for the HW+ / SW decoder modes.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
