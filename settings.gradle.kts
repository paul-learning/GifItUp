rootProject.name = "GifItUp"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
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
