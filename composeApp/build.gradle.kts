plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)

    // Android: shared UI as a library; the APK is built by :androidApp (AGP 9 separates app and KMP modules).
    android {
        namespace = "app.gifitup.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            // MlsClient implementations (ADR-0007); built into AppGraph by each composition root.
            implementation(projects.mls)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
    }
}

// The web bundle must contain the freshly built crypto package, not a stale copy in node_modules (#66 CR-1, CR-11).
tasks
    .matching { it.name.startsWith("wasmJsBrowser") && (it.name.contains("Webpack") || it.name.contains("Distribution")) }
    .configureEach {
        dependsOn(":mls:syncWasmPackage")
        inputs
            .dir(rootProject.layout.buildDirectory.dir("wasm/node_modules/gifitup-crypto"))
            .withPropertyName("cryptoPackage")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
