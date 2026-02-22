plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iplinks.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iplinks.player"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("en")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            // Sign with debug key (auto-uses ~/.android/debug.keystore)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // CORE ONLY - minimum dependencies for HLS player
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
}
