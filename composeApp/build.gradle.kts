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
        // The web composition root (Main.kt) builds the MlsClient factory (ADR-0007); Android's lives in :androidApp.
        wasmJsMain.dependencies {
            implementation(projects.mls)
        }
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
    }
}

// The web bundle must contain the freshly built crypto package, not a stale copy in node_modules (#66 CR-1, CR-11).
// Every webpack task (bundles and the dev/production run servers) gets the synced package (#67 CR-4).
tasks
    .withType<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>()
    .configureEach {
        dependsOn(":mls:syncWasmPackage")
        inputs
            .dir(rootProject.layout.buildDirectory.dir("wasm/node_modules/gifitup-crypto"))
            .withPropertyName("cryptoPackage")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
