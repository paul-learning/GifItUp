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

// SPIKE (#3): OpenMLS on Android. Needs Rust + cargo-ndk, so it is opt-in: -Pgifitup.rust=true
// (or gifitup.rust=true in ~/.gradle/gradle.properties). See docs/spikes/openmls-android.md.
if (providers.gradleProperty("gifitup.rust").orNull == "true") {
    include(":spikes:mls-android")
}
