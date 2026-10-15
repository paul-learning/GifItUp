// :mls — the platform implementations of the MlsClient interface from :shared (ADR-0007): uniffi bindings
// on Android/JVM, wasm-bindgen on the web. Only the app modules depend on it; it needs the Rust toolchain
// (crypto/rust-toolchain.toml), wasm-bindgen-cli (version = the crate's wasm-bindgen pin) and, for the
// Android .so files, cargo-ndk. The task classes move to build-logic/ with #34.

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val cryptoDir = rootProject.layout.projectDirectory.dir("crypto")
val androidAbis = listOf("arm64-v8a", "x86_64")
val minSdkVersion = libs.versions.android.minSdk.get().toInt()

// Each build flavour gets its own cargo target dir, so artifacts can't leak between them (#11 plan SEC-5).
val hostTargetDir = layout.buildDirectory.dir("cargo/host")
val wasmTargetDir = layout.buildDirectory.dir("cargo/wasm")
val androidTargetDir = layout.buildDirectory.dir("cargo/android")
val wasmPkgDir = layout.buildDirectory.dir("wasm-pkg")
val uniffiKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")

val crateSources =
    files(
        cryptoDir.file("Cargo.toml"),
        cryptoDir.file("Cargo.lock"),
        cryptoDir.file("rust-toolchain.toml"),
        cryptoDir.dir("src"),
        cryptoDir.file("uniffi-bindgen.rs"),
    )

/** Host library name of the crate on this OS (what JNA loads in JVM tests). */
fun hostLibraryName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("windows") -> "gifitup_crypto.dll"
        os.contains("mac") -> "libgifitup_crypto.dylib"
        else -> "libgifitup_crypto.so"
    }
}

/** Builds the host library (debug, for JVM tests) and generates the uniffi Kotlin bindings from it. */
abstract class UniffiBindgen
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:Internal
        abstract val crateDir: DirectoryProperty

        @get:Input
        abstract val libraryName: Property<String>

        @get:OutputDirectory
        abstract val targetDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun generate() {
            val crate = crateDir.get().asFile
            val target = targetDir.get().asFile
            cargo(crate, "build", "--lib", "--locked", "--target-dir", target.absolutePath)
            val out = outputDir.get().asFile
            out.deleteRecursively()
            cargo(
                crate,
                "run",
                "--locked",
                "--target-dir",
                target.absolutePath,
                "--features",
                "bindgen",
                "--bin",
                "uniffi-bindgen",
                "--",
                "generate",
                "--library",
                target.resolve("debug/${libraryName.get()}").absolutePath,
                "--language",
                "kotlin",
                "--no-format",
                "--out-dir",
                out.absolutePath,
            )
        }

        private fun cargo(
            crate: File,
            vararg args: String,
        ) {
            exec.exec {
                workingDir = crate
                executable = "cargo"
                args(*args)
            }
        }
    }

/** Cross-compiles the crate for the Android ABIs with cargo-ndk (release profile). */
abstract class CargoNdkBuild
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:Internal
        abstract val crateDir: DirectoryProperty

        @get:Input
        abstract val abis: ListProperty<String>

        @get:Input
        abstract val platform: Property<Int>

        @get:Internal
        abstract val ndkDir: DirectoryProperty

        @get:Internal
        abstract val targetDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val args = mutableListOf("ndk")
            abis.get().forEach { args += listOf("-t", it) }
            args += listOf("-P", platform.get().toString(), "-o", outputDir.get().asFile.absolutePath)
            args += listOf("build", "--release", "--locked", "--target-dir", targetDir.get().asFile.absolutePath)
            exec.exec {
                workingDir = crateDir.get().asFile
                executable = "cargo"
                args(args)
                environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            }
        }
    }

/**
 * Builds the crate for wasm32 (release) and runs wasm-bindgen (`--target web`) into an npm package directory
 * that Kotlin consumes via `npm("gifitup-crypto", dir)`.
 */
abstract class WasmBindgenPackage
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:Internal
        abstract val crateDir: DirectoryProperty

        @get:Internal
        abstract val targetDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val crate = crateDir.get().asFile
            val target = targetDir.get().asFile
            val out = outputDir.get().asFile
            exec.exec {
                workingDir = crate
                executable = "cargo"
                args(
                    "build",
                    "--target",
                    "wasm32-unknown-unknown",
                    "--release",
                    "--locked",
                    "--target-dir",
                    target.absolutePath,
                )
            }
            out.deleteRecursively()
            exec.exec {
                workingDir = crate
                executable = "wasm-bindgen"
                args(
                    "--target",
                    "web",
                    "--out-dir",
                    out.absolutePath,
                    target.resolve("wasm32-unknown-unknown/release/gifitup_crypto.wasm").absolutePath,
                )
            }
            out.resolve("package.json").writeText(
                """
                {
                  "name": "gifitup-crypto",
                  "version": "0.0.0",
                  "private": true,
                  "type": "module",
                  "main": "gifitup_crypto.js",
                  "types": "gifitup_crypto.d.ts"
                }
                """.trimIndent(),
            )
        }
    }

val uniffiBindgen =
    tasks.register<UniffiBindgen>("uniffiBindgen") {
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        libraryName.set(hostLibraryName())
        targetDir.set(hostTargetDir)
        outputDir.set(uniffiKotlinDir)
    }

val wasmBindgenPackage =
    tasks.register<WasmBindgenPackage>("wasmBindgenPackage") {
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        targetDir.set(wasmTargetDir)
        outputDir.set(wasmPkgDir)
    }

// Kotlin validates npm(name, dir) at configuration time, before wasmBindgenPackage can run, so the
// directory has to exist up front (the task fills it before npm install).
wasmPkgDir.get().asFile.mkdirs()

kotlin {
    jvmToolchain(21)

    // Host target for the contract tests (uniffi bindings + host library through JNA, no emulator).
    jvm()
    android {
        namespace = "app.gifitup.mls"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = minSdkVersion
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // MlsClient, MlsClientFactory and their types are part of this module's API.
            api(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // uniffi's Kotlin output is plain JVM Kotlin: the same generated files and wrapper compile for both
        // JVM targets (a shared intermediate source set would need JNA in its metadata compilation).
        jvmMain {
            kotlin.srcDir(uniffiBindgen.map { it.outputDir })
            kotlin.srcDir("src/jniShared/kotlin")
            dependencies { implementation(libs.jna) }
        }
        androidMain {
            kotlin.srcDir(uniffiBindgen.map { it.outputDir })
            kotlin.srcDir("src/jniShared/kotlin")
            dependencies { implementation("${libs.jna.get()}@aar") }
        }
        wasmJsMain.dependencies {
            implementation(npm("gifitup-crypto", wasmPkgDir.get().asFile))
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// JVM contract tests load the host library built by uniffiBindgen. It is a declared input, so a change to the
// Rust code alone reruns the tests (and changes their build-cache key; #66 CR-1).
tasks.named<Test>("jvmTest") {
    dependsOn(uniffiBindgen)
    inputs
        .file(hostTargetDir.map { it.file("debug/${hostLibraryName()}") })
        .withPropertyName("hostLibrary")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("jna.library.path", hostTargetDir.get().dir("debug").asFile.absolutePath)
}

val cargoNdkBuild =
    tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        abis.set(androidAbis)
        platform.set(minSdkVersion)
        // AGP knows the SDK location (ANDROID_HOME or local.properties); the NDK version comes from the catalog.
        ndkDir.set(
            project.extensions
                .getByType<com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension>()
                .sdkComponents.sdkDirectory
                .map { it.dir("ndk/${libs.versions.android.ndk.get()}") },
        )
        targetDir.set(androidTargetDir)
        outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    }

// The npm package directory must be filled before Kotlin's npm/yarn install runs.
val rootNpmInstall = rootProject.tasks.matching { it.name == "kotlinWasmNpmInstall" || it.name == "wasmKotlinNpmInstall" }
rootNpmInstall.configureEach { dependsOn(wasmBindgenPackage) }

// Kotlin copies `file:` npm dependencies into node_modules once and may keep a stale copy when only the Rust
// code changed. This replaces the copy with the fresh package, and the browser tests declare the package as an
// input, so they rerun after any crate change (#66 CR-1). Bundling it into the web app (#11 PR 4) needs the same.
val syncWasmPackage =
    tasks.register<Sync>("syncWasmPackage") {
        dependsOn(rootNpmInstall)
        from(wasmBindgenPackage)
        into(rootProject.layout.buildDirectory.dir("wasm/node_modules/gifitup-crypto"))
    }
tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach {
    dependsOn(syncWasmPackage)
    inputs.files(wasmBindgenPackage).withPropertyName("wasmPackage").withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks
    .matching { it.name.contains("PackageJson", ignoreCase = true) }
    .configureEach { dependsOn(wasmBindgenPackage) }

// Generated bindings are not ours to format.
extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter { exclude { it.file.path.contains("${File.separator}build${File.separator}") } }
}
