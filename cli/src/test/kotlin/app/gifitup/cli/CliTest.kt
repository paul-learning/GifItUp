package app.gifitup.cli

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.domain.auth.AuthError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliTest {
    private val base = listOf("--server", "http://127.0.0.1:8080", "--state-dir", "/tmp/s")

    @Test
    fun parsesEachCommand() {
        val register = Invocation.parse(listOf("register") + base + listOf("--username", "ann", "--origin", "https://e2e.invalid"))
        assertEquals(Invocation("register", "http://127.0.0.1:8080", Path.of("/tmp/s"), "https://e2e.invalid", "ann", false), register)
        listOf("login", "devices", "reset").forEach { assertEquals(it, Invocation.parse(listOf(it) + base).command) }
        assertEquals(true, Invocation.parse(listOf("login") + base + "--remote-test-account").remoteTestAccount)
    }

    @Test
    fun badCommandLinesAreUsageErrors() {
        val cases =
            listOf(
                emptyList(),
                listOf("merge") + base,
                listOf("register") + base, // no username
                listOf("login") + base + listOf("--username", "ann"), // username only for register
                listOf("login", "--server", "x"), // no state dir
                listOf("login") + base + listOf("--server", "y"), // twice
                listOf("login") + base + listOf("--remote-test-account", "--remote-test-account"),
                listOf("login") + base + "--origin", // value missing
                listOf("login") + base + "--invite", // the invite never comes from argv (#79 SEC-6)
                listOf("login") + base + "extra",
            )
        cases.forEach { assertEquals(EXIT_USAGE, assertFailsWith<CliError>("$it") { Invocation.parse(it) }.exit) }
    }

    @Test
    fun theInviteMayBeTheCodeOrTheLink() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", inviteCodeFrom("AAAAAAAAAAAAAAAAAAAAAA\n"))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", inviteCodeFrom("  https://gifitup.app/invite#AAAAAAAAAAAAAAAAAAAAAA  "))
    }

    @Test
    fun everyErrorHasItsOwnFixedMessage() {
        val errors = ApiError.entries.map { AuthError.Api(it) } + AuthError.Local.entries
        val messages = errors.map(::message)
        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assert(it.isNotBlank()) }
    }
}
