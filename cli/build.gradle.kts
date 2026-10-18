// The dev CLI (#79): a JVM command-line client over the same use cases as the app, for agents, CI's end-to-end test and
// smoke tests with test accounts. A dev/test tool only: never shipped, never part of an app artifact, and no module
// depends on it (CLAUDE.md "Dev CLI"; checked by scripts/check-repo-hygiene.sh). Keys live unprotected in its state dir.
// This first part (PR B1) holds the state dir and the connection rules; the commands come with PR B2.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}
