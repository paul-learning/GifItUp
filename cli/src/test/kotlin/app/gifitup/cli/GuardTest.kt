package app.gifitup.cli

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.storage.StoredIdentity
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The CLI goes through TestAccountGuard before anything else: no invite is read, no MLS client made or restored, no key
 * generated, no request sent.
 */
class GuardTest {
    private val dir = Files.createTempDirectory("cli-guard-").resolve("state")
    private var invitesRead = 0
    private var requests = 0
    private val noMls =
        object : MlsClientFactory {
            override fun create(
                username: String,
                snapshotKey: ByteArray,
            ): MlsClient = error("no MLS client may be created")

            override fun restore(
                state: ByteArray,
                snapshotKey: ByteArray,
            ): MlsClient = error("no MLS client may be restored")
        }
    private val cli =
        Cli(
            engine = { MockEngine { error("no request may be sent (${++requests})") } },
            mls = noMls,
            random = { error("no key may be generated") },
            readInvite = { invitesRead++.let { "AAAAAAAAAAAAAAAAAAAAAA" } },
            out = PrintStream(ByteArrayOutputStream()),
            err = PrintStream(ByteArrayOutputStream()),
        )

    private fun run(
        command: String,
        args: List<String>,
    ) = runBlocking { cli.run(Invocation.parse(listOf(command, "--state-dir", dir.toString()) + args)) }

    private fun assertRefused(
        command: String,
        args: List<String>,
    ) = assertEquals(EXIT_REFUSED, assertFailsWith<CliError>("$command $args") { run(command, args) }.exit)

    @Test
    fun registerRefusesARealOriginWithoutTheFlagOrWithAPlainUsername() {
        listOf(
            listOf("--server", "https://gifitup.app", "--username", "cli_smoke"),
            listOf("--server", "https://gifitup.app", "--username", "ann", "--remote-test-account"),
            // A tunnel to the real server on a loopback port signs the real origin: still refused (#79 SEC-2).
            listOf("--server", "http://127.0.0.1:8443", "--origin", "https://gifitup.app", "--username", "cli_smoke"),
        ).forEach { assertRefused("register", it) }
        assertEquals(0, invitesRead)
        assertEquals(0, requests)
        assertFalse(dir.resolve("identity").exists())
    }

    @Test
    fun loginAndDevicesCheckTheStoredUsername() {
        // A state dir for a real-origin account without the cli_ prefix (#103 CR-1, SEC-13).
        runBlocking {
            val state = StateDir(dir)
            // With a key too, so without the guard LogIn would get as far as restoring the client (and fail the test).
            state.lock().use {
                state.keys.save(ByteArray(StateDir.KEY_BYTES))
                state.identities.save(StoredIdentity("https://gifitup.app", "ann", byteArrayOf(1), deviceId = "d"))
            }
        }
        listOf("login", "devices").forEach { command ->
            assertRefused(command, listOf("--server", "https://gifitup.app"))
            assertRefused(command, listOf("--server", "https://gifitup.app", "--remote-test-account"))
            assertRefused(command, listOf("--server", "http://127.0.0.1:8443", "--origin", "https://gifitup.app"))
        }
        assertEquals(0, requests)
    }
}
