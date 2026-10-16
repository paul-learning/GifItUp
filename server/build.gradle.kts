plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.gifitup.server.ApplicationKt")
}

dependencies {
    // The server depends on the wire models only, never on client code in :shared.
    implementation(projects.protocol)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core) {
        // Flyway's POM lists Lombok (an annotation processor) at compile scope; nothing needs it at runtime.
        exclude(group = "org.projectlombok", module = "lombok")
    }
    implementation(libs.flyway.database.postgresql) {
        exclude(group = "org.projectlombok", module = "lombok")
        // Pulled in for CockroachDB support only; we run PostgreSQL.
        exclude(group = "org.flywaydb", module = "flyway-database-cockroachdb")
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    // Ryuk (Testcontainers' reaper) runs as a privileged container with the Docker socket and is pulled by tag
    // (#12 round 0, SEC-13). The tests stop their container themselves instead.
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    // The database tests read the Postgres image pin from here; a changed pin must re-run them (#69 CR-2).
    inputs
        .file(rootProject.file("deploy/docker-compose.yml"))
        .withPropertyName("composeFile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
