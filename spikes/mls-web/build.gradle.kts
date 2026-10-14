// SPIKE (#4) — throwaway. Proves OpenMLS (crypto/) runs in the browser via wasm-bindgen and is callable
// from Kotlin/Wasm. The real wiring moves into :shared (wasmJsMain actual of the crypto facade) in #11.

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val cryptoDir = rootProject.layout.projectDirectory.dir("crypto")
val wasmPkgDir = layout.buildDirectory.dir("wasm-pkg")

/**
 * Builds crypto/ for wasm32 (release) and runs wasm-bindgen (`--target web`) into an npm package
 * directory that Kotlin consumes via `npm("gifitup-crypto", dir)`. Needs `wasm-bindgen-cli` with the
 * exact version of the `wasm-bindgen` crate (see crypto/Cargo.lock).
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

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val crate = crateDir.get().asFile
            val out = outputDir.get().asFile
            exec.exec {
                workingDir = crate
                executable = "cargo"
                args("build", "--target", "wasm32-unknown-unknown", "--release", "--locked")
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
                    crate.resolve("target/wasm32-unknown-unknown/release/gifitup_crypto.wasm").absolutePath,
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

val wasmBindgenPackage =
    tasks.register<WasmBindgenPackage>("wasmBindgenPackage") {
        sources.from(
            cryptoDir.file("Cargo.toml"),
            cryptoDir.file("Cargo.lock"),
            cryptoDir.file("rust-toolchain.toml"),
            cryptoDir.dir("src"),
        )
        crateDir.set(cryptoDir)
        outputDir.set(wasmPkgDir)
    }

// Kotlin validates npm(name, dir) at configuration time, before wasmBindgenPackage can run, so the
// directory has to exist up front (the task fills it before npm install).
wasmPkgDir.get().asFile.mkdirs()

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(npm("gifitup-crypto", wasmPkgDir.get().asFile))
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.coroutines.core)
        }
        wasmJsTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The npm package directory must exist before Kotlin's npm/yarn install runs.
rootProject.tasks
    .matching { it.name == "kotlinWasmNpmInstall" || it.name == "wasmKotlinNpmInstall" }
    .configureEach { dependsOn(wasmBindgenPackage) }
tasks
    .matching { it.name.contains("PackageJson", ignoreCase = true) }
    .configureEach { dependsOn(wasmBindgenPackage) }
