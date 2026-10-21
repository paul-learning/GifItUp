plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// The server origin a build starts with (ADR-0013): `-Pgifitup.defaultOrigin=https://...` for a real build. The user
// can change it in Settings while the device has no identity. Must be canonical (https, lowercase, no path).
val defaultOrigin = providers.gradleProperty("gifitup.defaultOrigin").orElse("https://gifitup.invalid")
val generateBuildDefaults by tasks.registering {
    val origin = defaultOrigin
    val out = layout.buildDirectory.dir("generated/buildDefaults/kotlin")
    inputs.property("origin", origin)
    outputs.dir(out)
    doLast {
        val value = origin.get()
        // The app's own canonical-origin rule (ApiEndpoint.isCanonicalOrigin), so a build never ships an origin the app
        // would refuse at start (#131 SEC-19).
        val label = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
        val match = Regex("^https://$label(?:\\.$label)*(?::([1-9][0-9]{0,4}))?$").matchEntire(value)
        val port = match?.groupValues?.get(1)?.toIntOrNull()
        require(match != null && value.length <= 256 && (port == null || (port <= 65_535 && port != 443))) {
            "gifitup.defaultOrigin must be a canonical https origin"
        }
        val file = out.get().file("app/gifitup/BuildDefaults.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package app.gifitup\n\ninternal object BuildDefaults {\n    const val DEFAULT_ORIGIN = \"$value\"\n}\n")
    }
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
            implementation(libs.ktor.client.js)
        }
        commonMain {
            kotlin.srcDir(generateBuildDefaults)
        }
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(libs.ktor.client.core)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
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
