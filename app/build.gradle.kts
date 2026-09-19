import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = "0.5.0"
// CI passes -PversionCode (scripts/version_code.sh for releases). A local build
// without it gets one above its release's code, so it installs over that release
// and the next release installs over it. Same formula as version_code.sh.
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) ->
        major * 1_000_000 + minor * 10_000 + patch * 100 + 1
    }

// Release signing: CI passes KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS /
// KEY_PASSWORD as environment variables; local builds read the gitignored
// signing.properties written by scripts/make_keystore.py. Signing is applied only
// when the keystore file exists, so PR builds without secrets still work. Use the
// SAME keystore locally and in CI: Android refuses to upgrade an app in place
// across signing keys.
val localSigning = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreFilePath: String? = System.getenv("KEYSTORE_FILE")
    ?: localSigning.getProperty("storeFile")?.let { rootProject.file(it).path }
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: localSigning.getProperty(prop)
val hasSigningConfig = keystoreFilePath != null && File(keystoreFilePath).exists()

android {
    namespace = "com.lperezmo.phonewhisper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lperezmo.phonewhisper"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = File(keystoreFilePath!!)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the Kotlin, AppCompat and Material code the app does not use
            // (about 11 MB -> a few MB). Keep rules for the libraries ship inside them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
