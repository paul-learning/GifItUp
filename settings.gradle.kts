rootProject.name = "GifItUp"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // androidx and com.android come only from Google Maven, never from another repository (#38 SEC-5).
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
            }
        }
        // com.google.* is split between Google Maven and Maven Central, so it can't be exclusive.
        google {
            mavenContent {
                includeGroupAndSubgroups("com.google")
            }
        }
        // The Gradle Plugin Portal serves only the ktlint plugin, and the ktlint plugin only comes from there
        // (#33). Every other plugin resolves from Google Maven or Maven Central.
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter { includeGroupAndSubgroups("org.jlleitschuh.gradle") }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // All repositories are declared here; projects (and plugins) may not add their own (#33).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // androidx and com.android come only from Google Maven, never from another repository (#38 SEC-5).
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
            }
        }
        // com.google.* is split between Google Maven and Maven Central, so it can't be exclusive.
        google {
            mavenContent {
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()

        // Tool distributions the Kotlin/Wasm plugin downloads (Node.js, Yarn, Binaryen). The plugin's own
        // project-level repositories are switched off in the root build (downloadBaseUrl = null), so these
        // are the only source, and each one may serve exactly one module. Patterns copied from KGP's setup tasks.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                    name = "Binaryen distributions"
                    patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

include(":protocol")
include(":shared")
include(":composeApp")
include(":androidApp")
include(":server")

// SPIKES (#3, #4): OpenMLS on Android and in the browser. They need Rust (+ cargo-ndk / wasm-bindgen-cli),
// so they are opt-in: -Pgifitup.rust=true (or gifitup.rust=true in ~/.gradle/gradle.properties).
// See docs/spikes/openmls-android.md and docs/spikes/openmls-web.md.
if (providers.gradleProperty("gifitup.rust").orNull == "true") {
    include(":spikes:mls-android")
    include(":spikes:mls-web")
}
