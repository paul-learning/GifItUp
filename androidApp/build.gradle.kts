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
 * no cleartext and no user certificate authority. The merged manifest is not checked (it needs the Android SDK).
 */
abstract class VerifyManifestHardening : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

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
        val application = parse(src.resolve("main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
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

        resources(src, "network_security_config.xml").forEach { problems += checkNetworkConfig(parse(it), it.name) }
        resources(src, "data_extraction_rules.xml").forEach { problems += checkExtractionRules(parse(it), it.name) }
        if (problems.isNotEmpty()) throw GradleException("Android hardening check failed:\n - " + problems.joinToString("\n - "))
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }

    /** Every copy of [name] in any source set or `xml*` qualifier directory; exactly the main one must exist. */
    private fun resources(
        src: File,
        name: String,
    ): List<File> {
        val all = src.walkTopDown().filter { it.name == name && it.parentFile.name.startsWith("xml") }.toList()
        val main = src.resolve("main/res/xml/$name")
        if (all !=
            listOf(main)
        ) {
            throw GradleException("$name must exist once, as src/main/res/xml/$name (a variant copy overrides it): $all")
        }
        return all
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
        marker.set(layout.buildDirectory.file("manifest-hardening.ok"))
    }

tasks.named("check") { dependsOn(verifyManifestHardening) }
