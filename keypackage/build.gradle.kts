// :keypackage reads and checks MLS KeyPackages for the server (ADR-0012) and holds the Ed25519 verification the
// server's login uses (ADR-0009). Pure Kotlin on the JDK: no dependencies, no Rust. `:mls` uses it in tests only,
// to check it against KeyPackages made by the real crate.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // KeyPackageSpec/buildKeyPackage: a test-side encoder, shared with :mls's differential test (never on a runtime classpath).
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotlin.test)
}
