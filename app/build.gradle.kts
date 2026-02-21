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
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
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
    
    // LEAKCANARY - Detecção de memory leaks (apenas debug)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")
    
    // TESTES
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("app.cash.turbine:turbine:1.0.0")
}

// ============================================
// TESTES AUTOMÁTICOS EM TODOS OS BUILDS
// ============================================

// Usar afterEvaluate para garantir que as tasks existam
afterEvaluate {
    tasks.findByName("assembleDebug")?.dependsOn("testDebugUnitTest")
    tasks.findByName("assembleRelease")?.dependsOn("testReleaseUnitTest")
}

// Relatório de cobertura
tasks.register("testReport") {
    dependsOn("testDebugUnitTest")
    doLast {
        val reportDir = file("build/reports/tests/testDebugUnitTest")
        println("\n" + "=".repeat(50))
        println("📊 TEST REPORT: ${reportDir.absolutePath}/index.html")
        println("=".repeat(50) + "\n")
    }
}
