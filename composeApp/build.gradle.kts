plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// The server origin a build starts with (ADR-0013): `-Pgifitup.defaultOrigin=https://...` for a real build. The user
// can change it in Settings while the device has no identity. Must be canonical (https, lowercase, no path).
val defaultOrigin = providers.gradleProperty("gifitup.defaultOrigin").orElse("https://gifitup.invalid")
// The version the Android release pipeline passes (`-Pgifitup.versionName=MAJOR.MINOR.PATCH`); "dev" for local builds and the web, which has
// no version of its own. Shown on the diagnostics screen and in its report, so it is held to a plain pattern.
val appVersion = providers.gradleProperty("gifitup.versionName").orElse("dev")
val generateBuildDefaults by tasks.registering {
    val origin = defaultOrigin
    val version = appVersion
    inputs.property("version", version)
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
        val versionValue = version.get()
        require(versionValue == "dev" || Regex("^[0-9]{1,3}\\.[0-9]{1,2}\\.[0-9]{1,2}$").matches(versionValue)) {
            "gifitup.versionName must be MAJOR.MINOR.PATCH"
        }
        val file = out.get().file("app/gifitup/BuildDefaults.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package app.gifitup\n\ninternal object BuildDefaults {\n    const val DEFAULT_ORIGIN = \"$value\"\n    const val VERSION = \"$versionValue\"\n}\n",
        )
    }
}

// A web bundle that is served to people must know its server (#27 SEC-6): the production webpack build refuses to run
// without an explicit `-Pgifitup.defaultOrigin`, so a bundle can't ship with the placeholder origin. Development runs
// and tests keep the placeholder. The value must equal the server's GIFITUP_PUBLIC_URL (ADR-0009); the deploy workflow
// takes both from the same variable.
val requireExplicitOrigin by tasks.registering {
    val explicit = providers.gradleProperty("gifitup.defaultOrigin")
    doLast {
        require(explicit.isPresent) { "A production web bundle needs -Pgifitup.defaultOrigin=https://<host> (canonical origin)" }
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
        if (name.contains("Production")) dependsOn(requireExplicitOrigin)
        inputs
            .dir(rootProject.layout.buildDirectory.dir("wasm/node_modules/gifitup-crypto"))
            .withPropertyName("cryptoPackage")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
