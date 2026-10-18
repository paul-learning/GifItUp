// The end-to-end test (#79 PR C): Postgres on the production image, then the real Migrate, Admin, server and dev CLI
// entry points from their installDist outputs, run as processes. Test code only, part of `check`, so it runs in CI's
// "Gradle check" job on every PR (Docker is already needed there). No module depends on this one, and it is the only
// build file besides settings.gradle.kts that may name the CLI (scripts/check-repo-hygiene.sh).

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(projects.server))
}

// The two installDist tasks have to exist before this module looks them up.
evaluationDependsOn(":cli")
evaluationDependsOn(":server")

val cliInstall = project(":cli").tasks.named<Sync>("installDist")
val serverInstall = project(":server").tasks.named<Sync>("installDist")

tasks.test {
    // Ryuk gets the Docker socket and is pulled by tag (#12 SEC-13); the fixture refuses to start without this (#79
    // round-0 SEC-4).
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    // Build inputs (review lessons): the entry points under test, and the compose file the Postgres image comes from.
    inputs
        .files(cliInstall)
        .withPropertyName("cliInstall")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(serverInstall)
        .withPropertyName("serverInstall")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .file(rootProject.file("deploy/docker-compose.yml"))
        .withPropertyName("composeFile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val cliDir = cliInstall.map { it.destinationDir.absolutePath }
    val serverDir = serverInstall.map { it.destinationDir.absolutePath }
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-De2e.cli=${cliDir.get()}", "-De2e.server=${serverDir.get()}") })
    // Process output only reaches the report through the test's own redacted dump (#79 round-0 SEC-9).
    testLogging { showStandardStreams = false }
}
