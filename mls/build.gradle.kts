// :mls — the platform implementations of the MlsClient interface from :shared (ADR-0007): uniffi bindings
// on Android/JVM, wasm-bindgen on the web. Only the app modules and the dev CLI (#79) depend on it; it needs the
// Rust toolchain (crypto/rust-toolchain.toml), wasm-bindgen-cli (version = the crate's wasm-bindgen pin) and, for
// the Android .so files, cargo-ndk. The task classes move to build-logic/ with #34.

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
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
val hostTestTargetDir = layout.buildDirectory.dir("cargo/host-test")
val wasmTargetDir = layout.buildDirectory.dir("cargo/wasm")
val wasmTestTargetDir = layout.buildDirectory.dir("cargo/wasm-test")
val androidTargetDir = layout.buildDirectory.dir("cargo/android")
val wasmPkgDir = layout.buildDirectory.dir("wasm-pkg")
val wasmTestPkgDir = layout.buildDirectory.dir("wasm-pkg-test")
val uniffiKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")

val crateSources =
    files(
        cryptoDir.file("Cargo.toml"),
        cryptoDir.file("Cargo.lock"),
        cryptoDir.file("rust-toolchain.toml"),
        cryptoDir.dir("src"),
        cryptoDir.file("uniffi-bindgen.rs"),
    )

/**
 * RUSTFLAGS for builds that ship: rewrite the build machine's cargo home and the repository checkout (crypto/ and
 * the cargo target dirs under mls/build), which would otherwise end up in panic-location strings of the .so/.wasm
 * files (account name, non-reproducible builds; #67 SEC-3, SEC-9). Setting CARGO_ENCODED_RUSTFLAGS replaces any
 * rustflags from a .cargo/config.toml for these builds (there is none; switch to `trim-paths` once it's stable).
 */
fun releaseRustFlags(repo: File): String {
    val cargoHome = System.getenv("CARGO_HOME") ?: File(System.getProperty("user.home"), ".cargo").path
    return listOf("--remap-path-prefix=$cargoHome=/cargo", "--remap-path-prefix=${repo.path}=/repo").joinToString("\u001f")
}

/**
 * Only one cargo build runs at a time: parallel cargo calls block on cargo's package-cache lock anyway, and on a
 * fresh machine each would make rustup install the pinned toolchain at the same moment and collide (#67 CR-1).
 */
abstract class CargoSerializer : BuildService<BuildServiceParameters.None>

val cargoSerializer = gradle.sharedServices.registerIfAbsent("cargoSerializer", CargoSerializer::class) { maxParallelUsages.set(1) }

/** The build machine's home directory in the spellings a binary may contain it. */
fun homePathVariants(): List<String> {
    val home = System.getProperty("user.home")
    return listOf(home, home.replace('\\', '/')).distinct()
}

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

        @get:Input
        abstract val rustFlags: Property<String>

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
                environment("CARGO_ENCODED_RUSTFLAGS", rustFlags.get())
            }
        }
    }

/** Builds the host library in debug with the `test-panic` feature, for the JVM panic tests only (ADR-0007). */
abstract class CargoHostTestLibrary
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:Internal
        abstract val crateDir: DirectoryProperty

        @get:OutputDirectory
        abstract val targetDir: DirectoryProperty

        @TaskAction
        fun build() {
            exec.exec {
                workingDir = crateDir.get().asFile
                executable = "cargo"
                args("build", "--lib", "--locked", "--features", "test-panic", "--target-dir", targetDir.get().asFile.absolutePath)
            }
        }
    }

/**
 * Fails if any file contains the `test-panic` marker symbol: release artifacts and the generated production
 * bindings must never contain the test-only panic trigger (#11 plan review SEC-5).
 */
abstract class VerifyNoTestPanic : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    /** Paths that must not appear either (the build machine's home), for release binaries; empty to skip. */
    @get:Input
    abstract val forbiddenPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val markers = listOf("gifitup_test_panic_marker") + forbiddenPaths.get()
        val needles = markers.map { it.encodeToByteArray() }
        val files = artifacts.asFileTree.files
        check(files.isNotEmpty()) { "no artifacts to scan" }
        val tainted = files.filter { file -> file.readBytes().let { bytes -> needles.any { bytes.indexOf(it) >= 0 } } }
        check(tainted.isEmpty()) { "test-panic code or build-machine paths found in: ${tainted.joinToString()}" }
        logger.lifecycle("No test-panic marker in ${files.size} files.")
        stamp.get().asFile.writeText("ok\n")
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1
}

/**
 * Builds the crate for wasm32 and runs wasm-bindgen (`--target web`) into an npm package directory that Kotlin
 * consumes via `npm(packageName, dir)`. The production package is a release build; the test package is a debug
 * build with the `test-panic` feature under its own name, so the two can never be mixed up (ADR-0007).
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

        @get:Input
        abstract val testBuild: Property<Boolean>

        @get:Input
        abstract val packageName: Property<String>

        @get:Input
        abstract val rustFlags: Property<String>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val crate = crateDir.get().asFile
            val target = targetDir.get().asFile
            val out = outputDir.get().asFile
            val test = testBuild.get()
            val cargoArgs = mutableListOf("build", "--target", "wasm32-unknown-unknown", "--locked")
            cargoArgs += if (test) listOf("--features", "test-panic") else listOf("--release")
            cargoArgs += listOf("--target-dir", target.absolutePath)
            exec.exec {
                workingDir = crate
                executable = "cargo"
                args(cargoArgs)
                if (!test) environment("CARGO_ENCODED_RUSTFLAGS", rustFlags.get())
            }
            val profile = if (test) "debug" else "release"
            out.deleteRecursively()
            exec.exec {
                workingDir = crate
                executable = "wasm-bindgen"
                args(
                    "--target",
                    "web",
                    "--out-dir",
                    out.absolutePath,
                    target.resolve("wasm32-unknown-unknown/$profile/gifitup_crypto.wasm").absolutePath,
                )
            }
            out.resolve("package.json").writeText(
                """
                {
                  "name": "${packageName.get()}",
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
        usesService(cargoSerializer)
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        libraryName.set(hostLibraryName())
        targetDir.set(hostTargetDir)
        outputDir.set(uniffiKotlinDir)
    }

// The host library uniffiBindgen builds (debug, without the test-panic feature) for the dev CLI's distribution
// (#79 PR B): the CLI consumes this module too (besides the app modules), a dev/test tool that never ships (ADR-0007).
val hostNativeLibrary by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(hostNativeLibrary.name, uniffiBindgen.map { it.targetDir.file("debug/${hostLibraryName()}").get() }) {
    builtBy(uniffiBindgen)
}

val wasmBindgenPackage =
    tasks.register<WasmBindgenPackage>("wasmBindgenPackage") {
        usesService(cargoSerializer)
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        targetDir.set(wasmTargetDir)
        testBuild.set(false)
        packageName.set("gifitup-crypto")
        rustFlags.set(releaseRustFlags(rootProject.projectDir))
        outputDir.set(wasmPkgDir)
    }

val wasmBindgenTestPackage =
    tasks.register<WasmBindgenPackage>("wasmBindgenTestPackage") {
        usesService(cargoSerializer)
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        targetDir.set(wasmTestTargetDir)
        testBuild.set(true)
        packageName.set("gifitup-crypto-test")
        rustFlags.set("")
        outputDir.set(wasmTestPkgDir)
    }

val hostTestLibrary =
    tasks.register<CargoHostTestLibrary>("hostTestLibrary") {
        usesService(cargoSerializer)
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        targetDir.set(hostTestTargetDir)
    }

// Kotlin validates npm(name, dir) at configuration time, before the package tasks can run, so the
// directories have to exist up front (the tasks fill them before npm install).
wasmPkgDir.get().asFile.mkdirs()
wasmTestPkgDir.get().asFile.mkdirs()

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
        // Debug build with the test-panic feature, only for the trap test (ADR-0007).
        wasmJsTest.dependencies {
            implementation(npm("gifitup-crypto-test", wasmTestPkgDir.get().asFile))
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

// JVM tests load a debug host library with the test-panic feature (same FFI surface as production, so the
// generated bindings match). It is a declared input, so a change to the Rust code alone reruns the tests (and
// changes their build-cache key; #66 CR-1).
tasks.named<Test>("jvmTest") {
    dependsOn(uniffiBindgen, hostTestLibrary)
    inputs
        .file(hostTestTargetDir.map { it.file("debug/${hostLibraryName()}") })
        .withPropertyName("hostLibrary")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("jna.library.path", hostTestTargetDir.get().dir("debug").asFile.absolutePath)
}

val cargoNdkBuild =
    tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        usesService(cargoSerializer)
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
        rustFlags.set(releaseRustFlags(rootProject.projectDir))
        outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    }

// The Android .so files are part of the AAR, so every app that depends on :mls ships them.
extensions.configure<com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
    }
}

// The npm package directory must be filled before Kotlin's npm/yarn install runs.
val rootNpmInstall = rootProject.tasks.matching { it.name == "kotlinWasmNpmInstall" || it.name == "wasmKotlinNpmInstall" }
rootNpmInstall.configureEach { dependsOn(wasmBindgenPackage, wasmBindgenTestPackage) }

// Kotlin copies `file:` npm dependencies into node_modules once and may keep a stale copy when only the Rust
// code changed. This replaces the copy with the fresh package, and the browser tests declare the package as an
// input, so they rerun after any crate change (#66 CR-1). Bundling it into the web app (#11 PR 4) needs the same.
val syncWasmPackage =
    tasks.register<Sync>("syncWasmPackage") {
        dependsOn(rootNpmInstall)
        from(wasmBindgenPackage)
        into(rootProject.layout.buildDirectory.dir("wasm/node_modules/gifitup-crypto"))
    }
val syncWasmTestPackage =
    tasks.register<Sync>("syncWasmTestPackage") {
        dependsOn(rootNpmInstall)
        from(wasmBindgenTestPackage)
        into(rootProject.layout.buildDirectory.dir("wasm/node_modules/gifitup-crypto-test"))
    }
tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach {
    dependsOn(syncWasmPackage, syncWasmTestPackage)
    inputs.files(wasmBindgenPackage).withPropertyName("wasmPackage").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(wasmBindgenTestPackage).withPropertyName("wasmTestPackage").withPathSensitivity(PathSensitivity.RELATIVE)
}

// The production wasm package never contains the test-only panic trigger (#11 plan SEC-5). The generated bindings
// are scanned as a tripwire (the trigger adds no FFI function, so it can only show up there if that changes).
// What actually ships (the web bundle, the .so files inside the APKs) is scanned in CI as well (#67 SEC-1).
val verifyNoTestPanic =
    tasks.register<VerifyNoTestPanic>("verifyNoTestPanic") {
        artifacts.from(wasmBindgenPackage, uniffiBindgen.flatMap { it.outputDir })
        forbiddenPaths.set(emptyList())
        stamp.set(layout.buildDirectory.file("verify/no-test-panic.txt"))
    }
// The release wasm on its own also must not carry the build machine's home path (#67 SEC-9).
val verifyReleaseWasmPaths =
    tasks.register<VerifyNoTestPanic>("verifyReleaseWasmPaths") {
        artifacts.from(wasmBindgenPackage.map { it.outputDir.file("gifitup_crypto_bg.wasm") })
        forbiddenPaths.set(homePathVariants())
        stamp.set(layout.buildDirectory.file("verify/release-wasm-paths.txt"))
    }
tasks.named("check") { dependsOn(verifyNoTestPanic, verifyReleaseWasmPaths) }
val verifyNoTestPanicAndroid =
    tasks.register<VerifyNoTestPanic>("verifyNoTestPanicAndroid") {
        artifacts.from(cargoNdkBuild)
        forbiddenPaths.set(homePathVariants())
        stamp.set(layout.buildDirectory.file("verify/no-test-panic-android.txt"))
    }
tasks
    .matching { it.name.contains("PackageJson", ignoreCase = true) }
    .configureEach { dependsOn(wasmBindgenPackage) }

// Generated bindings are not ours to format.
extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter { exclude { it.file.path.contains("${File.separator}build${File.separator}") } }
}

// Every Android build of the .so files is scanned, locally too, not only when CI names the task (#67 SEC-10).
cargoNdkBuild.configure { finalizedBy(verifyNoTestPanicAndroid) }
