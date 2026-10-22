package app.gifitup.shared.domain.contacts

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AcceptContact] has no app caller until a fingerprint can be shown (ADR-0014, #18 PR 3/4): an accept in an app
 * without something to compare is a blind click. Only `:cli` (test accounts, which says so) may use it. A text check
 * over the app modules' sources, so wiring it in by mistake fails here.
 */
class NoAppAcceptTest {
    private val root: Path =
        generateSequence(Path.of("").toAbsolutePath()) {
            it.parent
        }.first { Files.exists(it.resolve("settings.gradle.kts")) }

    @Test
    fun noAppModuleReferencesAcceptContact() {
        val appSources = listOf("composeApp", "androidApp", "mls").map { root.resolve(it).resolve("src") }.filter { it.isDirectory() }
        assertTrue(appSources.size >= 2, "the app modules' sources were found")
        val hits =
            appSources.flatMap { dir ->
                Files.walk(dir).use { paths ->
                    paths
                        .asSequence()
                        .filter { it.extension == "kt" && "AcceptContact" in it.readText() }
                        .map {
                            root
                                .relativize(
                                    it,
                                ).toString()
                        }.toList()
                }
            }
        assertEquals(emptyList(), hits, "AcceptContact must not be wired into an app module before the fingerprint exists")
    }
}
