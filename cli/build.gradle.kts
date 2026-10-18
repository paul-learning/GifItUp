// The dev CLI (#79): a JVM command-line client over the same use cases as the app, for agents, CI's end-to-end test and
// smoke tests with test accounts. A dev/test tool only: never shipped, never part of an app artifact, and no module
// depends on it (CLAUDE.md "Dev CLI"; checked by scripts/check-repo-hygiene.sh). Keys live unprotected in its state dir.

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.gifitup.cli.MainKt")
    applicationName = "gifitup-cli"
    // The crate's host library ships in lib/native; the start scripts point JNA at it (placeholder replaced below).
    applicationDefaultJvmArgs = listOf("-Djna.library.path=__APP_HOME__/lib/native")
}

val nativeLibrary by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(projects.shared)
    implementation(projects.mls)
    implementation(libs.ktor.client.core)
    // The JDK's own HttpClient: no extra network stack (#79 plan).
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.core)
    // Ktor logs through slf4j; the no-op provider keeps every log line (and slf4j's "no provider" warning) out of the
    // CLI's output and CI logs (#79 plan SEC-9). Any other provider on this classpath fails the hygiene check.
    runtimeOnly(libs.slf4j.nop)
    nativeLibrary(project(path = ":mls", configuration = "hostNativeLibrary"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
}

distributions {
    main {
        contents {
            from(nativeLibrary) { into("lib/native") }
        }
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.writeText(unixScript.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("__APP_HOME__", "%APP_HOME%"))
    }
}
