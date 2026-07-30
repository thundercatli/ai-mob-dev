import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing is optional: it reads keystore.properties (gitignored, see
 * keystore.example.properties) or the matching environment variables so CI can supply them as
 * secrets. When nothing is configured the release build falls back to the debug key below, which
 * keeps `assembleRelease` producing an installable APK instead of an unsigned one.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(propertyName: String, envName: String): String? =
    keystoreProperties.getProperty(propertyName) ?: System.getenv(envName)

android {
    namespace = "com.devhc.aidevmob"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devhc.aidevmob"
        minSdk = 26
        targetSdk = 36
        // CI overrides these for tagged builds so the APK reports the released version.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // We only ship a prebuilt arm64 frpc binary (packaged as jniLibs/arm64-v8a/libfrpc.so).
            abiFilters += "arm64-v8a"
        }
    }

    val releaseSigningConfig = signingValue("storeFile", "STORE_FILE")
        ?.let { path -> rootProject.file(path).takeIf { it.exists() } }
        ?.let { keystore ->
            signingConfigs.create("release") {
                storeFile = keystore
                storePassword = signingValue("storePassword", "STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }

    buildTypes {
        release {
            // R8 is left off on purpose: sshj and BouncyCastle resolve a lot through reflection and
            // ServiceLoader, so shrinking needs keep rules that have not been worked out or tested yet.
            isMinifyEnabled = false
            signingConfig = releaseSigningConfig ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // libfrpc.so is actually an executable, not a real shared library: it must be extracted
            // to a real file under applicationInfo.nativeLibraryDir so ProcessBuilder can exec it
            // (with extractNativeLibs=false, modern AGP keeps libs zipped inside the APK instead).
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Pull-to-refresh in the file browser, which is the gesture a file list is expected to have.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // SSH transport (SFTP/exec/shell over SSH). Used to attach to the remote tmux session.
    implementation("com.hierynomus:sshj:0.40.0")

    // sshj pulls this in transitively for KEX/host-key crypto, but we also reference
    // BouncyCastleProvider directly (AiDevMobApplication) to replace Android's stripped-down
    // built-in "BC" provider, so declare it explicitly to pin the compile-time API.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")

    // Only used by the launch smoke test in src/androidTest: ActivityScenario opens the app for real
    // on an emulator, which is the only way to catch a crash in an Activity's own initialisation.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
