package app.gifitup.e2e

import app.gifitup.server.db.EndToEndDatabase
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The walking skeleton end to end (#79): a fresh database on the production Postgres image, the real `Migrate`, the
 * server on loopback with the origin `https://e2e.invalid`, `Admin create-invite`, then the dev CLI's commands, all as
 * processes from their installDist outputs.
 *
 * No raw process output goes into an assertion message. The known invite codes and passwords must appear in none of
 * the captured output (CLI, server, Migrate, Admin's stderr, Postgres); on a failure the test prints that output with
 * them and anything shaped like a code or token redacted, which is what CI's log shows (#79 round-0 SEC-9).
 */
class EndToEndTest {
    private val cliHome = Path.of(System.getProperty("e2e.cli"))
    private val serverLib = Path.of(System.getProperty("e2e.server")).resolve("lib")
    private val work by lazy { Files.createTempDirectory("gifitup-e2e-") }
    private val processes by lazy { Processes(work) }
    private val secrets = mutableListOf<String>()

    @Test
    fun registerLoginAndDevicesAgainstTheRealServer() {
        val db = EndToEndDatabase.fresh()
        secrets += listOf(db.adminPassword, db.appPassword)
        val appPasswordFile = processes.secretFile("app-password", db.appPassword)
        val dbEnv = mapOf("DB_URL" to db.jdbcUrl, "DB_USER" to db.appUser, "DB_PASSWORD_FILE" to "$appPasswordFile")
        val port = freePort()
        var server: Process? = null
        try {
            migrate(db, appPasswordFile)
            server = startServer(dbEnv, port)
            val cli = Cli(port)
            val used = createInvite(dbEnv)
            walkingSkeleton(cli, used)
            refusals(cli, used, createInvite(dbEnv))
            server.stopAndWait()
            assertNoSecretInOutput()
        } catch (failure: Throwable) {
            server?.stopAndWait()
            dumpRedactedOutput()
            throw failure
        } finally {
            // Raw output, the secret files and the test accounts' keys: only the redacted dump outlives the test.
            work.toFile().deleteRecursively()
        }
    }

    /** The image copies `server/build/install/server/lib`: the fixtures and Testcontainers must not be in it (SEC-11). */
    @Test
    fun theServerDistributionHoldsNoTestCode() {
        val jars = serverLib.listDirectoryEntries().map { it.name }
        assertTrue(jars.any { it.startsWith("server") }, "the server's own jar is there")
        assertFalse(jars.any { "test-fixtures" in it || it.startsWith("testcontainers") }, "no test code in $jars")
    }

    private fun migrate(
        db: EndToEndDatabase.Fresh,
        appPasswordFile: Path,
    ) {
        val env =
            mapOf(
                "DB_URL" to db.jdbcUrl,
                "DB_ADMIN_USER" to db.adminUser,
                "DB_ADMIN_PASSWORD_FILE" to "${processes.secretFile("admin-password", db.adminPassword)}",
                "DB_APP_PASSWORD_FILE" to "$appPasswordFile",
            )
        assertEquals(0, processes.run("migrate", serverEntryPoint("MigrateKt"), env).exit, "Migrate exit code")
    }

    private fun startServer(
        dbEnv: Map<String, String>,
        port: Int,
    ): Process {
        val env = dbEnv + mapOf("GIFITUP_PUBLIC_URL" to ORIGIN, "HOST" to "127.0.0.1", "PORT" to "$port")
        val server = processes.start("server", serverEntryPoint("ApplicationKt"), env)
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val health = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(2)).build()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Processes.TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!server.isAlive) fail("the server exited with ${server.exitValue()} before it was ready")
            val status = runCatching { http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull()
            if (status == HTTP_OK) return server
            Thread.sleep(POLL_MILLIS)
        }
        server.stopAndWait()
        fail("the server wasn't ready within ${Processes.TIMEOUT_SECONDS} s")
    }

    /** An admin invite; returns the link, which Admin prints once to stdout (kept out of the output checks). */
    private fun createInvite(dbEnv: Map<String, String>): String {
        val env = dbEnv + ("GIFITUP_PUBLIC_URL" to ORIGIN)
        val admin = processes.run("admin", serverEntryPoint("AdminKt") + "create-invite", env, excludeStdout = true)
        assertEquals(0, admin.exit, "Admin exit code")
        // Logback writes the pool's startup lines to stdout too, so the link is the one line that starts with the origin.
        val link = admin.stdout.lines().singleOrNull { it.startsWith("$ORIGIN/") } ?: fail("Admin printed no invite link for $ORIGIN")
        secrets += link.substringAfterLast('#')
        return link
    }

    /** register (invite on stdin) → login → devices: one device, the registered one. */
    private fun walkingSkeleton(
        cli: Cli,
        link: String,
    ) {
        val ann = work.resolve("state-ann")
        val registered = cli.run("register", ann, "--username", "e2e_ann", stdin = link)
        assertEquals(0, registered.exit, "register exit code")
        val deviceId = DEVICE_LINE.matchEntire(registered.stdout.trim())?.groupValues?.get(1) ?: fail("register printed no device id")

        val login = cli.run("login", ann)
        assertEquals(0, login.exit, "login exit code")
        assertTrue(login.stdout.startsWith("logged in as device $deviceId,"), "login names the registered device")

        val devices = cli.run("devices", ann)
        assertEquals(0, devices.exit, "devices exit code")
        val lines = devices.stdout.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, "devices lists exactly one device")
        assertTrue(lines.single().startsWith("$deviceId  created "), "devices lists the registered device")
    }

    /**
     * A used invite is refused with the closed error. A signature for another origin gets 401 and leaves the invite
     * unused, so the same invite then works with the right origin (#79 round-0 SEC-10).
     */
    private fun refusals(
        cli: Cli,
        used: String,
        link: String,
    ) {
        val reused = cli.run("register", work.resolve("state-bob"), "--username", "e2e_bob", stdin = used)
        assertEquals(EXIT_SERVER, reused.exit, "a used invite: exit code")
        // stderr also holds the CLI's prompt (no newline), so the message follows it on the same line.
        assertTrue("gifitup-cli: invite not accepted" in reused.stderr, "a used invite: the closed message")

        val wrongOrigin =
            cli.run("register", work.resolve("state-carol-1"), "--username", "e2e_carol", stdin = link, origin = "https://other.invalid")
        assertEquals(EXIT_SERVER, wrongOrigin.exit, "another origin: exit code")
        assertTrue("gifitup-cli: not authorized" in wrongOrigin.stderr, "another origin: 401")

        val retried = cli.run("register", work.resolve("state-carol-2"), "--username", "e2e_carol", stdin = link)
        assertEquals(0, retried.exit, "the same invite with the right origin: exit code")
    }

    private fun assertNoSecretInOutput() {
        val outputs = processes.capturedOutput() + ("postgres" to EndToEndDatabase.serverLog())
        outputs.forEach { (name, text) ->
            secrets.forEachIndexed { i, secret -> assertFalse(secret in text, "secret #$i appears in the $name output") }
        }
    }

    private fun dumpRedactedOutput() {
        val outputs = processes.capturedOutput() + ("postgres" to runCatching { EndToEndDatabase.serverLog() }.getOrDefault(""))
        outputs.forEach { (name, text) -> println("----- $name -----\n" + redact(text, secrets)) }
    }

    private fun serverEntryPoint(mainClass: String) =
        listOf(Processes.java, "-cp", "$serverLib${File.separator}*", "app.gifitup.server.$mainClass")

    /** The installed CLI's start script against the loopback server, signing [ORIGIN] unless told otherwise. */
    private inner class Cli(
        private val port: Int,
    ) {
        fun run(
            command: String,
            stateDir: Path,
            vararg args: String,
            stdin: String = "",
            origin: String = ORIGIN,
        ): Finished {
            val script = cliHome.resolve("bin").resolve(if (Processes.windows) "gifitup-cli.bat" else "gifitup-cli").toString()
            val launcher = if (Processes.windows) listOf("cmd", "/c", script) else listOf(script)
            val line = listOf(command, "--server", "http://127.0.0.1:$port", "--origin", origin, "--state-dir", "$stateDir") + args
            return processes.run("cli $command", launcher + line, emptyMap(), stdin = stdin + "\n")
        }
    }

    private fun Process.stopAndWait() {
        destroy()
        if (!waitFor(Processes.TIMEOUT_SECONDS, TimeUnit.SECONDS)) destroyForcibly().waitFor()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val ORIGIN = "https://e2e.invalid"
        const val HTTP_OK = 200
        const val POLL_MILLIS = 200L
        const val EXIT_SERVER = 1
        val DEVICE_LINE = Regex("^registered e2e_ann as device ([0-9a-f-]{36})$")
    }
}
