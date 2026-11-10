plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.schachassistent"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.schachassistent"
        minSdk = 26          // TYPE_APPLICATION_OVERLAY braucht API 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"

        // Stockfish liegt nur für 64-Bit-ARM bei (alle aktuellen Samsung-Geräte).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        // Stockfish wird später als "libstockfish.so" in src/main/jniLibs/<abi>/ abgelegt.
        // Legacy-Packaging entpackt sie nach nativeLibraryDir, wo sie ausführbar ist.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":chesscore"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.material:material:1.12.0")


    // Nächste Schritte (noch auskommentiert):
    // implementation("org.opencv:opencv:4.10.0")   // OpenCV-Android (Maven Central) für Template-Matching
}
