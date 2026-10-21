plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    // The dev CLI (:cli) and the end-to-end test (:e2e) run on the JVM; jvmTest is also the fast host test run.
    jvm()
    android {
        namespace = "app.gifitup.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.protocol)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The canonical-origin cases shared with the crate and the server (#94 CR-4): a change to the file reruns the test.
tasks.named<Test>("jvmTest") {
    inputs.file(rootProject.file("server/src/test/resources/origin-rule-cases.txt"))
}
