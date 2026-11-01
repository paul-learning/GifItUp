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
        listOf("keypackages-refill", "keypackages-status").forEach { assertEquals(it, Invocation.parse(listOf(it) + base).command) }
        val claim = Invocation.parse(listOf("keypackages-claim") + base + listOf("--target", "ben"))
        assertEquals("ben", claim.target)
        assertEquals(Path.of("/tmp/link"), Invocation.parse(listOf("invite-create") + base + listOf("--link-file", "/tmp/link")).linkFile)
        assertEquals("invites", Invocation.parse(listOf("invites") + base).command)
        val revoke = Invocation.parse(listOf("invite-revoke") + base + listOf("--id", ID))
        assertEquals(ID, revoke.inviteId)
    }

    @Test
    fun parsesTheDeliveryCommandsWithExactlyTheirOptions() {
        val group = "AQIDBAUGBwgJCgsMDQ4PEA"
        val create = Invocation.parse(listOf("group-create") + base + listOf("--device", ID))
        assertEquals(ID, create.device)
        val post = Invocation.parse(listOf("group-post") + base + listOf("--group", group))
        assertEquals(16, post.group?.size)
        assertEquals(7L, Invocation.parse(listOf("group-fetch") + base + listOf("--group", group, "--seq", "7")).seq)
        assertEquals(null, Invocation.parse(listOf("group-fetch") + base + listOf("--group", group)).seq)
        assertEquals(ID, Invocation.parse(listOf("welcome-ack") + base + listOf("--welcome", ID)).welcome)
        assertEquals("welcomes", Invocation.parse(listOf("welcomes") + base).command)

        val bad =
            listOf(
                listOf("group-create") + base, // no device
                listOf("group-create") + base + listOf("--device", "not-a-uuid"),
                listOf("group-post") + base, // no group
                listOf("group-post") + base + listOf("--group", "AAAA"), // a group id under 16 bytes
                listOf("group-post") + base + listOf("--group", group, "--text", "hello"), // readable content never goes out
                listOf("group-status") + base + listOf("--group", group, "--seq", "1"), // another command's option
                listOf("group-ack") + base + listOf("--group", group, "--seq", "-1"),
                listOf("group-fetch") + base + listOf("--group", group, "--seq", "1x"),
                listOf("devices") + base + listOf("--group", group), // the group options belong to the group commands
                listOf("welcomes") + base + listOf("--welcome", ID),
            )
        bad.forEach { args -> assertFailsWith<CliError>(args.joinToString(" ")) { Invocation.parse(args) } }
    }

    @Test
    fun parsesTheChatCommandsWithExactlyTheirOptions() {
        val group = "AQIDBAUGBwgJCgsMDQ4PEA"
        assertEquals("ben", Invocation.parse(listOf("chat-create") + base + listOf("--target", "ben")).target)
        listOf("chat-add", "chat-remove").forEach {
            val parsed = Invocation.parse(listOf(it) + base + listOf("--group", group, "--target", "ben"))
            assertEquals("ben", parsed.target)
            assertEquals(16, parsed.group?.size)
        }
        listOf("chat-send", "chat-sync", "chat-resume", "chat-history").forEach {
            assertEquals(it, Invocation.parse(listOf(it) + base + listOf("--group", group)).command)
        }
        assertEquals("chat-welcomes", Invocation.parse(listOf("chat-welcomes") + base).command)
        assertEquals(
            setOf(
                "chat-create",
                "chat-add",
                "chat-remove",
                "chat-send",
                "chat-sync",
                "chat-history",
                "chat-resume",
                "chat-welcomes",
                "chat-groups",
            ),
            Invocation.CHAT_COMMANDS,
        )

        val bad =
            listOf(
                listOf("chat-create") + base, // no target
                listOf("chat-add") + base + listOf("--target", "ben"), // no group
                listOf("chat-add") + base + listOf("--group", group), // no target
                listOf("chat-send") + base + listOf("--group", group, "--text", "hello"), // the text comes from stdin only
                listOf("chat-sync") + base + listOf("--group", group, "--seq", "3"),
                listOf("chat-welcomes") + base + listOf("--group", group),
                listOf("chat-send") + base + listOf("--group", "AAAA"), // a group id under 16 bytes
                listOf("group-post") + base + listOf("--group", group, "--target", "ben"), // the wire driver takes no target
            )
        bad.forEach { args -> assertFailsWith<CliError>(args.joinToString(" ")) { Invocation.parse(args) } }
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
                listOf("keypackages-claim") + base, // no target
                listOf("keypackages-claim") + base + listOf("--target", "ben", "--target", "cat"),
                listOf("keypackages-refill") + base + listOf("--target", "ben"), // a target only for claim
                listOf("login") + base + listOf("--target", "ben"),
                listOf("invite-create") + base, // no link file: the code never goes to the screen
                listOf("invites") + base + listOf("--link-file", "/tmp/link"), // a link file only for invite-create
                listOf("invite-revoke") + base, // no id
                listOf("invite-revoke") + base + listOf("--id", ID.uppercase()), // ids are canonical
                listOf("invite-revoke") + base + listOf("--id", "../devices"), // never a path
                listOf("invites") + base + listOf("--id", ID),
            )
        cases.forEach { assertEquals(EXIT_USAGE, assertFailsWith<CliError>("$it") { Invocation.parse(it) }.exit) }
    }

    @Test
    fun aMessageCannotMoveTheCursorOrReorderTheLineAroundIt() {
        val hostile = "a\u001b[2Jb\u202Ec\u2028d\u200Be\ng"
        assertEquals("a?[2Jb?c?d?e?g", printable(hostile))
        assertEquals("hello wörld 👍", printable("hello wörld 👍"))
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

    private companion object {
        const val ID = "0b6f3c1e-8a3e-4d1c-9a54-3f1a2b4c5d6e"
    }
}
