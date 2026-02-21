plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iplinks.player"
    compileSdk = 34

    // Performance: buildConfig desativado (não usado)
    buildFeatures {
        buildConfig = false
    }

    defaultConfig {
        applicationId = "com.iplinks.player"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("en")
    }

    // Debug keystore - usa padrão do Android ou cria um novo
    signingConfigs {
        getByName("debug") {
            val keystoreFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storeFile = keystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
            enableUnitTestCoverage = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            // Assina com debug keystore para distribuição
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    
    testNamespace = "com.iplinks.player.test"
}

dependencies {
    // CORE
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // MEDIA3 EXOPLAYER
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    
    // TESTES
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("app.cash.turbine:turbine:1.0.0")
}

// Testes automáticos em todos os builds
afterEvaluate {
    tasks.findByName("assembleDebug")?.dependsOn("testDebugUnitTest")
    tasks.findByName("assembleRelease")?.dependsOn("testReleaseUnitTest")
}
