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
 * No raw process output goes into an assertion message. The known invite codes and passwords (and their random parts,
 * in case a log escapes the quotes around them) must appear in none of the captured output (CLI, server, Migrate,
 * Admin without its link line, Postgres), and the processes' output holds nothing shaped like a session token, which
 * the test never sees. On a failure the test prints that output with all of it redacted, which is what CI's log shows
 * (#79 round-0 SEC-9, #104 SEC-1..SEC-3).
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
        // Both passwords end in a random UUID; that part alone also counts, so an escaped copy is caught (#104 SEC-1).
        secrets += listOf(db.adminPassword, db.appPassword).flatMap { listOf(it, it.takeLast(UUID_LENGTH)) }
        val appPasswordFile = processes.secretFile("app-password", db.appPassword)
        val dbEnv = mapOf("DB_URL" to db.jdbcUrl, "DB_USER" to db.appUser, "DB_PASSWORD_FILE" to "$appPasswordFile")
        val port = freePort()
        var server: Process? = null
        var failed = false
        try {
            migrate(db, appPasswordFile)
            server = startServer(dbEnv, port)
            val cli = Cli(port)
            val used = createInvite(dbEnv)
            walkingSkeleton(cli, used)
            refusals(cli, used, createInvite(dbEnv))
            keyPackages(cli, createInvite(dbEnv))
            server.stopAndWait()
            assertNoSecretInOutput()
        } catch (failure: Throwable) {
            failed = true
            server?.stopAndWait()
            dumpRedactedOutput()
            throw failure
        } finally {
            // Raw output, the secret files and the test accounts' keys: only the redacted dump outlives the test. A
            // leftover is never silent: it fails a passing test, and is reported next to an earlier failure (#104 CR-3).
            if (!work.toFile().deleteRecursively()) {
                if (failed) println("couldn't delete the work dir $work") else fail("couldn't delete the work dir $work")
            }
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
        val health = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(2)).build()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Processes.TIMEOUT_SECONDS)
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { http ->
            while (System.nanoTime() < deadline) {
                if (!server.isAlive) fail("the server exited with ${server.exitValue()} before it was ready")
                val status = runCatching { http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull()
                if (status == HTTP_OK) return server
                Thread.sleep(POLL_MILLIS)
            }
        }
        server.stopAndWait()
        fail("the server wasn't ready within ${Processes.TIMEOUT_SECONDS} s")
    }

    /** An admin invite; returns the link, which Admin prints once to stdout. The rest of its stdout is checked too. */
    private fun createInvite(dbEnv: Map<String, String>): String {
        val env = dbEnv + ("GIFITUP_PUBLIC_URL" to ORIGIN)
        val admin = processes.run("admin", serverEntryPoint("AdminKt") + "create-invite", env, excludeStdout = true)
        assertEquals(0, admin.exit, "Admin exit code")
        // Logback writes the pool's startup lines to stdout too, so the link is the one line that starts with the origin.
        val link = admin.stdout.lines().singleOrNull { it.startsWith("$ORIGIN/") } ?: fail("Admin printed no invite link for $ORIGIN")
        secrets += link.substringAfterLast('#')
        processes.record(
            "admin stdout without the link",
            admin.stdout
                .lines()
                .filterNot { it == link }
                .joinToString("\n"),
        )
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

    /**
     * The KeyPackage directory with real KeyPackages from the crate (#14, ADR-0012): the server's strict reader accepts
     * what the crate makes, a claim hands one out and deletes it, the pool runs empty, and the limits and the single
     * "no such member" answer hold. `e2e_ann` and `e2e_carol` were registered by the steps before.
     */
    private fun keyPackages(
        cli: Cli,
        daveInvite: String,
    ) {
        val ann = work.resolve("state-ann")
        val carol = work.resolve("state-carol-2")

        fun ok(
            command: String,
            state: Path,
            vararg args: String,
        ): String {
            val finished = cli.run(command, state, *args)
            assertEquals(0, finished.exit, "$command exit code")
            return finished.stdout.trim()
        }
        assertEquals("uploaded 50, 50 available on the server", ok("keypackages-refill", ann))
        assertEquals("50 available on the server (low-water mark 20)", ok("keypackages-status", ann))
        assertEquals("uploaded 0, 50 available on the server", ok("keypackages-refill", ann), "above the mark nothing is created")

        // Each claim hands out one KeyPackage that the crate accepts and removes it from the pool.
        repeat(2) { n ->
            val line = ok("keypackages-claim", carol, "--target", "e2e_ann")
            assertTrue(CLAIMED_USABLE.matches(line), "claim ${n + 1}: one usable KeyPackage for e2e_ann")
            assertTrue("${49 - n} available" in ok("keypackages-status", ann), "claim ${n + 1} deleted its KeyPackage")
        }
        val third = cli.run("keypackages-claim", carol, "--target", "e2e_ann")
        assertEquals(EXIT_SERVER, third.exit, "a third claim of one pair in a day: exit code")
        assertTrue("gifitup-cli: rate limited" in third.stderr, "a third claim: 429")

        // An unknown username and one's own look the same.
        listOf("e2e_nobody", "e2e_carol").forEach {
            val missing = cli.run("keypackages-claim", carol, "--target", it)
            assertEquals(EXIT_SERVER, missing.exit, "claim of $it: exit code")
            assertTrue("gifitup-cli: no such member" in missing.stderr, "claim of $it: the one answer")
        }

        // A member who never uploaded: the device is listed, without a KeyPackage.
        val dave = work.resolve("state-dave")
        assertEquals(0, cli.run("register", dave, "--username", "e2e_dave", stdin = daveInvite).exit, "register dave")
        assertTrue(CLAIMED_EMPTY.matches(ok("keypackages-claim", carol, "--target", "e2e_dave")), "an empty pool")
    }

    private fun assertNoSecretInOutput() {
        val processOutput = processes.capturedOutput()
        (processOutput + ("postgres" to EndToEndDatabase.serverLog())).forEach { (name, text) ->
            secrets.forEachIndexed { i, secret -> assertFalse(secret in text, "secret #$i appears in the $name output") }
        }
        // Session tokens are never known to the test, so their shape is what's checked (#104 SEC-3).
        processOutput.forEach { (name, text) ->
            assertFalse(TOKEN_SHAPED.containsMatchIn(text), "a token-shaped string in the $name output")
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
        const val UUID_LENGTH = 36
        val CLAIMED_USABLE = Regex("^[0-9a-f-]{36} {2}usable \\(credential e2e_ann\\)$")
        val CLAIMED_EMPTY = Regex("^[0-9a-f-]{36} {2}empty \\(no KeyPackage left\\)$")
        val DEVICE_LINE = Regex("^registered e2e_ann as device ([0-9a-f-]{36})$")
    }
}
