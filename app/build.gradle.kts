import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.devhc.aidevmob"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devhc.aidevmob"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // We only ship a prebuilt arm64 frpc binary (packaged as jniLibs/arm64-v8a/libfrpc.so).
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // SSH transport (SFTP/exec/shell over SSH). Used to attach to the remote tmux session.
    implementation("com.hierynomus:sshj:0.40.0")

    // sshj pulls this in transitively for KEX/host-key crypto, but we also reference
    // BouncyCastleProvider directly (AiDevMobApplication) to replace Android's stripped-down
    // built-in "BC" provider, so declare it explicitly to pin the compile-time API.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
}
