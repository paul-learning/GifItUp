plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Dependency locking (#33): every resolvable configuration of every project, plus the plugin classpath,
// resolves exactly the versions in the committed gradle.lockfile files. Update with --write-locks (see
// docs/dependencies.md).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

// Kotlin/Wasm would add its own project-level repositories for Node.js, Yarn and Binaryen downloads. Those are
// forbidden (FAIL_ON_PROJECT_REPOS); settings.gradle.kts declares them centrally instead (#33).
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec>().downloadBaseUrl.set(null as String?)
        // ktor-client-core 3.6.0 declares a ws version that a ws advisory covers (#135; fixed in ws 8.21.0). Dependabot
        // can't update kotlin-js-store/wasm/yarn.lock (no package.json: Kotlin generates it), so the version is pinned
        // here (catalog: npm-ws). On every Ktor bump, check the ws it declares and drop the pin once that is past the
        // advisory (docs/dependencies.md, "npm").
        the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", libs.versions.npm.ws.get())
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
    }
}

val detektKotlinVersion = libs.versions.detektKotlin.get()
val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // KMP source sets are not picked up by default; lint every src/<sourceSet>/kotlin dir.
        source.setFrom(file("src").listFiles().orEmpty().map { it.resolve("kotlin") }.filter { it.isDirectory })
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
    }
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlinVersion)
        }
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
    }
}
