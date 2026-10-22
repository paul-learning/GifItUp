import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.gifitup.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.gifitup"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // crypto/ is built for these ABIs only (:mls, ADR-0007); on any other ABI the app would crash on first
        // use, so it isn't offered there at all. Also keeps unused 32-bit JNA libraries out of the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.composeApp)
    // Composition root builds the MlsClient factory (ADR-0004, ADR-0007); also brings the .so files.
    implementation(projects.mls)
    implementation(libs.androidx.activity.compose)
    // The HTTP engine the composition root hands to the shared API client (ADR-0013); HttpURLConnection, no OkHttp.
    implementation(libs.ktor.client.android)
    // AppGraph's engine type is Ktor's; the shared UI uses it, so the app module sees it too.
    implementation(libs.ktor.client.core)
}

// No APK is written before the .so files passed the test-panic and build-path scan (ADR-0007, #67 SEC-12).
// Only the APK packaging tasks: other "package…" tasks (resources) also run in `check`, which has no cargo-ndk.
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    dependsOn(":mls:verifyNoTestPanicAndroid")
}

/**
 * Asserts the hardening of the Android shell on the sources (#54, SEC-M0-7), so a change that weakens it fails
 * `check` instead of passing review unnoticed. Allow-lists, not deny-lists: the one manifest and the one copy of each
 * resource are the only ones allowed to exist (a build-type manifest or an `xml-v31` copy would override them), the
 * backup and transfer rules must exclude every storage domain and may include none, and the network config may name
 * no cleartext and no user certificate authority. The build file's own settings are read through the Android DSL: no
 * build type but `debug` may be debuggable, and this build file may not use the Android DSL words that relocate a
 * source set's manifest or resources (the check looks for them as text, so it stays best effort; a relocated file
 * would escape the scan). The merged manifest is not checked (it needs the Android SDK), nor
 * is a library's manifest (the manifest merger lets the app's attributes win). The app's own manifest must request
 * exactly one permission, `INTERNET`: without it every network call fails and the app only says "no answer". A new kind of section in the backup rules
 * (Android 16 added `<cross-platform-transfer>` for iOS) fails the check on purpose: it needs a deliberate decision.
 */
abstract class VerifyManifestHardening : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** The names of the build types whose `isDebuggable` is true. */
    @get:Input
    abstract val debuggableBuildTypes: ListProperty<String>

    /** This build file, scanned for the words that move a manifest or resource directory out of `src/`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildScript: RegularFileProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val src = sourceDir.get().asFile
        val problems = mutableListOf<String>()
        val manifests = src.walkTopDown().filter { it.name == "AndroidManifest.xml" }.toList()
        if (manifests.map { it.relativeTo(src).path.replace('\\', '/') } != listOf("main/AndroidManifest.xml")) {
            problems += "only src/main may have an AndroidManifest.xml (another one can override the attributes): $manifests"
        }
        val permissions = declaredPermissions(src.resolve("main/AndroidManifest.xml"))
        if (permissions != setOf(INTERNET)) {
            problems += "the manifest must request exactly $INTERNET (the app can't reach its server without it), got $permissions"
        }
        debuggableBuildTypes.get().filter { it != "debug" }.forEach { problems += "build type '$it' is debuggable; only 'debug' may be" }
        // The pattern is built from pieces, so this very file doesn't match it.
        val relocating = Regex("source" + "Sets|src" + "File|src" + "Dir")
        relocating.findAll(buildScript.get().asFile.readText()).map { it.value }.toSet().forEach {
            problems += "build.gradle.kts uses '$it', which can move a manifest or resource directory out of the scanned src/"
        }
        val application = parse(src.resolve("main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as? Element
        if (application == null) {
            problems += "AndroidManifest.xml has no <application>"
            throw GradleException("Android hardening check failed:\n - " + problems.joinToString("\n - "))
        }
        mapOf(
            "allowBackup" to "false",
            "fullBackupContent" to "false",
            "usesCleartextTraffic" to "false",
            "networkSecurityConfig" to "@xml/network_security_config",
            "dataExtractionRules" to "@xml/data_extraction_rules",
        ).forEach { (name, want) ->
            val got = application.getAttributeNS(ANDROID_NS, name)
            if (got != want) problems += "<application android:$name> is '$got', must be '$want'"
        }
        if (application.hasAttributeNS(ANDROID_NS, "debuggable")) problems += "<application> must not set android:debuggable"

        resources(src, "network_security_config.xml", problems).forEach { problems += checkNetworkConfig(parse(it), it.name) }
        resources(src, "data_extraction_rules.xml", problems).forEach { problems += checkExtractionRules(parse(it), it.name) }
        if (problems.isNotEmpty()) throw GradleException("Android hardening check failed:\n - " + problems.joinToString("\n - "))
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }

    /** The one copy of [name] to check; none (and a problem) unless the main one is the only copy in any source set or `xml*` directory. */
    private fun resources(
        src: File,
        name: String,
        problems: MutableList<String>,
    ): List<File> {
        val all = src.walkTopDown().filter { it.name == name && it.parentFile.name.startsWith("xml") }.toList()
        val main = src.resolve("main/res/xml/$name")
        if (all == listOf(main)) return all
        problems += "$name must exist once, as src/main/res/xml/$name (a variant copy overrides it): $all"
        return emptyList()
    }

    private fun checkNetworkConfig(
        root: Element,
        file: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        val base = root.getElementsByTagName("base-config").item(0) as? Element
        if (base?.getAttribute("cleartextTrafficPermitted") !=
            "false"
        ) {
            problems += "$file: <base-config> must set cleartextTrafficPermitted=\"false\""
        }
        all(root).forEach { e ->
            if (e.hasAttribute("cleartextTrafficPermitted") && e.getAttribute("cleartextTrafficPermitted") != "false") {
                problems += "$file: <${e.tagName}> permits cleartext traffic"
            }
            if (e.tagName == "certificates" && e.getAttribute("src") != "system") problems += "$file: only src=\"system\" certificates"
            if (e.tagName == "debug-overrides") problems += "$file: no <debug-overrides>"
        }
        return problems
    }

    private fun checkExtractionRules(
        root: Element,
        file: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        val sections = children(root)
        if (sections.map { it.tagName }.sorted() != listOf("cloud-backup", "device-transfer")) {
            problems += "$file: needs exactly <cloud-backup> and <device-transfer>, got ${sections.map { it.tagName }}"
        }
        sections.forEach { section ->
            val rules = children(section)
            rules.filter { it.tagName != "exclude" }.forEach {
                problems +=
                    "$file: <${section.tagName}> may hold only <exclude>, got <${it.tagName}>"
            }
            val excluded = rules.filter { it.tagName == "exclude" && !it.hasAttribute("path") }.map { it.getAttribute("domain") }.toSet()
            (DOMAINS - excluded).forEach { problems += "$file: <${section.tagName}> doesn't exclude the whole domain '$it'" }
        }
        return problems
    }

    /** Every `uses-permission*` element of the manifest, by name (the allow-list is the one permission the app needs). */
    private fun declaredPermissions(manifest: File): Set<String> =
        children(parse(manifest))
            .filter { it.tagName.startsWith("uses-permission") }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
            .toSet()

    private fun children(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun all(e: Element): List<Element> = listOf(e) + children(e).flatMap { all(it) }

    private fun parse(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder()
            .parse(file)
            .documentElement

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val INTERNET = "android.permission.INTERNET"
        val DOMAINS =
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
    }
}

val verifyManifestHardening =
    tasks.register<VerifyManifestHardening>("verifyManifestHardening") {
        sourceDir.set(layout.projectDirectory.dir("src"))
        debuggableBuildTypes.set(provider { android.buildTypes.filter { it.isDebuggable }.map { it.name } })
        buildScript.set(layout.projectDirectory.file("build.gradle.kts"))
        marker.set(layout.buildDirectory.file("manifest-hardening.ok"))
    }

tasks.named("check") { dependsOn(verifyManifestHardening) }
