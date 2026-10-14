// SPIKE (#3) — throwaway. Proves OpenMLS (crypto/) runs on Android via uniffi + cargo-ndk.
// The real wiring moves into :shared (androidMain actual of the crypto facade) in #11.

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

val cryptoDir = rootProject.layout.projectDirectory.dir("crypto")
val androidAbis = listOf("arm64-v8a", "x86_64")
val minSdkVersion = libs.versions.android.minSdk.get().toInt()

android {
    namespace = "app.gifitup.spike.mls"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = minSdkVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += androidAbis }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("${libs.jna.get()}@aar")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

/** Cross-compiles crypto/ for the Android ABIs with cargo-ndk (release profile). */
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

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val args = mutableListOf("ndk")
            abis.get().forEach { args += listOf("-t", it) }
            args += listOf("-P", platform.get().toString(), "-o", outputDir.get().asFile.absolutePath)
            args += listOf("build", "--release", "--locked")
            exec.exec {
                workingDir = crateDir.get().asFile
                executable = "cargo"
                args(args)
                environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            }
        }
    }

/** Builds the host library (debug, unstripped) and generates the uniffi Kotlin bindings from it. */
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

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun generate() {
            val crate = crateDir.get().asFile
            exec.exec {
                workingDir = crate
                executable = "cargo"
                args("build", "--lib", "--locked")
            }
            val os = System.getProperty("os.name").lowercase()
            val libName =
                when {
                    os.contains("windows") -> "gifitup_crypto.dll"
                    os.contains("mac") -> "libgifitup_crypto.dylib"
                    else -> "libgifitup_crypto.so"
                }
            val out = outputDir.get().asFile
            out.deleteRecursively()
            exec.exec {
                workingDir = crate
                executable = "cargo"
                args(
                    "run",
                    "--locked",
                    "--features",
                    "bindgen",
                    "--bin",
                    "uniffi-bindgen",
                    "--",
                    "generate",
                    "--library",
                    crate.resolve("target/debug/$libName").absolutePath,
                    "--language",
                    "kotlin",
                    "--no-format",
                    "--out-dir",
                    out.absolutePath,
                )
            }
        }
    }

val crateSources =
    files(
        cryptoDir.file("Cargo.toml"),
        cryptoDir.file("Cargo.lock"),
        cryptoDir.file("rust-toolchain.toml"),
        cryptoDir.dir("src"),
        cryptoDir.file("uniffi-bindgen.rs"),
    )

val cargoNdkBuild =
    tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        abis.set(androidAbis)
        platform.set(minSdkVersion)
        ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
        outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    }

val uniffiBindgen =
    tasks.register<UniffiBindgen>("uniffiBindgen") {
        sources.from(crateSources)
        crateDir.set(cryptoDir)
        outputDir.set(layout.buildDirectory.dir("generated/uniffi/kotlin"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgen::outputDir)
    }
}
