plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.gifitup.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.gifitup"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // crypto/ is built for these ABIs only (:mls, ADR-0007); on any other ABI the app would crash on first
        // use, so it isn't offered there at all. Also keeps unused 32-bit JNA libraries out of the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.composeApp)
    // Composition root builds the MlsClient factory (ADR-0004, ADR-0007); also brings the .so files.
    implementation(projects.mls)
    implementation(libs.androidx.activity.compose)
}

// No APK is written before the .so files passed the test-panic and build-path scan (ADR-0007, #67 SEC-12).
tasks.matching { it.name.startsWith("package") }.configureEach { dependsOn(":mls:verifyNoTestPanicAndroid") }
