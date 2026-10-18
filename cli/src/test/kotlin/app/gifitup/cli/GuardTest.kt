package app.gifitup.cli

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The CLI goes through TestAccountGuard before anything else: no invite is read, no key made, nothing sent. */
class GuardTest {
    private val dir = Files.createTempDirectory("cli-guard-").resolve("state")
    private var invitesRead = 0
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
        Cli({
            Java.create()
        }, noMls, {
            error(
                "no key may be generated",
            )
        }, { invitesRead++.let { "AAAAAAAAAAAAAAAAAAAAAA" } }, PrintStream(ByteArrayOutputStream()))

    private fun register(vararg args: String) =
        runBlocking { cli.run(Invocation.parse(listOf("register", "--state-dir", dir.toString()) + args)) }

    @Test
    fun aRealOriginIsRefusedWithoutTheFlagOrWithAPlainUsername() {
        val cases =
            listOf(
                listOf("--server", "https://gifitup.app", "--username", "cli_smoke"),
                listOf("--server", "https://gifitup.app", "--username", "ann", "--remote-test-account"),
                // A tunnel to the real server on a loopback port signs the real origin: still refused (#79 SEC-2).
                listOf("--server", "http://127.0.0.1:8443", "--origin", "https://gifitup.app", "--username", "cli_smoke"),
            )
        cases.forEach { assertEquals(EXIT_REFUSED, assertFailsWith<CliError>("$it") { register(*it.toTypedArray()) }.exit) }
        assertEquals(0, invitesRead)
        assertFalse(dir.resolve("identity").exists())
    }
}
