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
 * No app module can trust a contact except through the Friends screen's holder in `:shared`, which only accepts an
 * observation it showed with its emojis (ADR-0014, #18 PR 4). The accept use case, its result type, the reviewed
 * observation, the dev CLI's opt-in and the book's own `accept` are named nowhere in the app modules; the types
 * enforce it (`AcceptContact.reviewed` is internal, `blindly` needs an opt-in), and this text check catches an
 * attempt to route around them.
 */
class NoAppAcceptTest {
    private val root: Path =
        generateSequence(Path.of("").toAbsolutePath()) {
            it.parent
        }.first { Files.exists(it.resolve("settings.gradle.kts")) }

    private val unblock =
        Regex("AcceptContact|AcceptResult|ReviewedObservation|BlindAcceptForDevCli|acceptContact|\\.blindly\\(|\\.accept\\(")

    @Test
    fun noAppModuleReferencesAcceptContact() {
        val appSources = listOf("composeApp", "androidApp", "mls").map { root.resolve(it).resolve("src") }.filter { it.isDirectory() }
        assertTrue(appSources.size >= 2, "the app modules' sources were found")
        val hits =
            appSources.flatMap { dir ->
                Files.walk(dir).use { paths ->
                    paths
                        .asSequence()
                        .filter { it.extension == "kt" && unblock.containsMatchIn(it.readText()) }
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
