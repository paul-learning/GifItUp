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
import java.util.UUID
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

    /**
     * The texts and the GIF the chat step sends: they may appear in the CLI's own output, never in the server's or the
     * database's.
     */
    private val plaintexts = mutableListOf<String>()

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
            // The GIF provider's key (#40): a secret file like the passwords. It, and its tail, must appear in no output.
            val gifKey = "e2e_" + UUID.randomUUID().toString().replace("-", "")
            secrets += listOf(gifKey, gifKey.takeLast(UUID_LENGTH - 4))
            server = startServer(dbEnv + ("GIF_PROVIDER_KEY_FILE" to "${processes.secretFile("gif-provider-key", gifKey)}"), port)
            val cli = Cli(port)
            val used = createInvite(dbEnv)
            walkingSkeleton(cli, used)
            refusals(cli, used, createInvite(dbEnv))
            keyPackages(cli, createInvite(dbEnv))
            invites(cli)
            contacts(cli)
            trust(cli, dbEnv)
            groups(cli, dbEnv)
            chat(cli, dbEnv)
            history(cli, dbEnv)
            gifProvider(cli, dbEnv, port, gifKey)
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
            assertTrue(claimedUsable("e2e_ann").matches(line), "claim ${n + 1}: one crate-valid KeyPackage for e2e_ann")
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

    /**
     * A member's own invites (#17, ADR-0010): the link goes to a new file only, a new member registers with it, the
     * list shows who used it, a revoked invite is refused, and the limit of 5 open invites holds. `e2e_dave` was
     * registered by the step before and hasn't logged in since (10 logins per device per hour).
     */
    private fun invites(cli: Cli) {
        val dave = work.resolve("state-dave")
        val erinLink = createdLink(cli, dave, "link-erin")
        assertEquals(0, cli.run("register", work.resolve("state-erin"), "--username", "e2e_erin", stdin = erinLink).exit, "register erin")

        val frankFile = work.resolve("link-frank")
        val frankLink = createdLink(cli, dave, frankFile.name)
        val frankId = inviteId(cli.run("invites", dave).stdout, "open")
        assertEquals("invite $frankId revoked", cli.run("invite-revoke", dave, "--id", frankId).stdout.trim())
        val revokedAgain = cli.run("invite-revoke", dave, "--id", frankId)
        assertEquals(EXIT_SERVER, revokedAgain.exit, "a second revoke: exit code")
        assertTrue("gifitup-cli: no open invite" in revokedAgain.stderr, "a second revoke: not found")
        val frank = cli.run("register", work.resolve("state-frank"), "--username", "e2e_frank", stdin = frankLink)
        assertEquals(EXIT_SERVER, frank.exit, "a revoked invite: exit code")
        assertTrue("gifitup-cli: invite not accepted" in frank.stderr, "a revoked invite: the closed message")

        // An existing file is never overwritten, and no invite is created for it.
        val existing = cli.run("invite-create", dave, "--link-file", "$frankFile")
        assertEquals(EXIT_REFUSED, existing.exit, "an existing link file: exit code")
        val listed =
            cli
                .run("invites", dave)
                .stdout
                .lines()
                .filter { it.isNotBlank() }
        assertEquals(2, listed.size, "dave has the two invites")
        assertTrue(listed.any { it.contains("  used  ") && it.endsWith("  used by e2e_erin") }, "the used invite names erin")
        assertTrue(listed.any { it.startsWith("$frankId  revoked  ") }, "the revoked invite")

        // At most 5 open invites: the 6th is refused and leaves no link file behind.
        val erin = work.resolve("state-erin")
        repeat(MAX_OPEN_INVITES) { createdLink(cli, erin, "link-open-$it") }
        val sixth = cli.run("invite-create", erin, "--link-file", "${work.resolve("link-sixth")}")
        assertEquals(EXIT_SERVER, sixth.exit, "a 6th open invite: exit code")
        assertTrue("gifitup-cli: too many open invites" in sixth.stderr, "a 6th open invite: the limit")
        assertFalse(Files.exists(work.resolve("link-sixth")), "no link file for a refused invite")
    }

    /**
     * The member list (#18, ADR-0014): an inviter lists the account that joined with their invite, and that account
     * lists its inviter; the list pins nothing (both are unconfirmed); a root account has no inviter. `e2e_erin` joined
     * with `e2e_dave`'s invite in the step before. A request without a session can't be sent through the CLI (it
     * always logs in): that 401 is a server test.
     */
    private fun contacts(cli: Cli) {
        fun lines(state: String) =
            cli
                .run(
                    "contacts",
                    work.resolve(state),
                ).also { assertEquals(0, it.exit, "contacts of $state: exit code") }
                .stdout
                .lines()
                .filter {
                    it.isNotBlank()
                }
        val dave = lines("state-dave").single()
        assertTrue(CONTACT_LINE.matches(dave) && dave.contains("  e2e_erin  unconfirmed  devices 1"), "dave lists erin, not yet pinned")
        val erin = lines("state-erin").single()
        assertTrue(CONTACT_LINE.matches(erin) && erin.contains("  e2e_dave  unconfirmed  devices 1"), "erin lists dave, not yet pinned")
    }

    /**
     * The Delivery Service (#21, ADR-0015) through the real server and the CLI's wire-level test driver: a group is
     * created with a welcome for another device, the welcome is fetched once and acknowledged, messages arrive in
     * order with their cursors, a device added later sees only what came after its commit, a non-member and a removed
     * device get the one "no such" answer, leaving works, and the freshness fields name the sender's last message.
     * Fresh accounts (eve, fay, gus): every command logs in, and a device may log in 10 times an hour.
     */
    private fun groups(
        cli: Cli,
        dbEnv: Map<String, String>,
    ) {
        fun register(name: String): Pair<Path, String> {
            val state = work.resolve("state-$name")
            val registered = cli.run("register", state, "--username", "e2e_$name", stdin = createInvite(dbEnv))
            assertEquals(0, registered.exit, "register $name")
            val id = Regex("^registered e2e_$name as device ([0-9a-f-]{36})$").matchEntire(registered.stdout.trim())?.groupValues?.get(1)
            return state to (id ?: fail("register $name printed no device id"))
        }

        fun ok(
            state: Path,
            command: String,
            vararg args: String,
        ): String {
            val finished = cli.run(command, state, *args)
            assertEquals(0, finished.exit, "$command exit code: ${finished.stderr}")
            return finished.stdout.trim()
        }

        fun refused(
            state: Path,
            command: String,
            vararg args: String,
        ) {
            val finished = cli.run(command, state, *args)
            assertEquals(EXIT_SERVER, finished.exit, "$command exit code")
            assertTrue("gifitup-cli: no such member" in finished.stderr, "$command: the one not-found answer")
        }
        val (eve, eveId) = register("eve")
        val (fay, fayId) = register("fay")
        val (gus, gusId) = register("gus")

        val created = ok(eve, "group-create", "--device", fayId)
        val group = Regex("^created group (\\S+) at seq 1, epoch 1$").matchEntire(created)?.groupValues?.get(1) ?: fail("create: $created")

        val welcome = ok(fay, "welcomes")
        assertTrue(Regex("^welcome [0-9a-f-]{36} group $group \\(64 bytes\\)$").matches(welcome), "fay's welcome: $welcome")
        assertEquals("no pending welcomes", ok(eve, "welcomes"), "only the target sees a welcome")
        assertEquals("welcome acknowledged", ok(fay, "welcome-ack", "--welcome", welcome.split(" ")[1]))
        assertEquals("no pending welcomes", ok(fay, "welcomes"))

        assertEquals("posted at seq 2", ok(eve, "group-post", "--group", group))
        val fetched = ok(fay, "group-fetch", "--group", group).lines()
        assertEquals("message 2 application from $eveId: 64 bytes", fetched[0], "fay sees the message after its join point only")
        assertEquals("retained 1..2", fetched[1])
        assertEquals("acknowledged up to 2", ok(fay, "group-ack", "--group", group, "--seq", "2"))

        val status = ok(eve, "group-status", "--group", group)
        assertTrue(
            Regex("^epoch 1, retained 1\\.\\.2, acknowledged 0, last sent seq 2 id \\S+$").matches(status),
            "eve's freshness anchor: $status",
        )

        // A device added later: a commit at the current epoch, a welcome for it, and no history from before it.
        assertEquals("committed at seq 3, epoch 2", ok(eve, "group-add", "--group", group, "--device", gusId))
        assertEquals("posted at seq 4", ok(eve, "group-post", "--group", group))
        assertTrue(ok(gus, "welcomes").startsWith("welcome "), "gus has a welcome")
        val gusSees = ok(gus, "group-fetch", "--group", group).lines()
        assertEquals("message 4 application from $eveId: 64 bytes", gusSees[0], "gus sees nothing from before its commit")

        // Not a member, and an unknown group: the same answer.
        refused(gus, "group-post", "--group", "AQIDBAUGBwgJCgsMDQ4PEA")
        // Leaving, and removal by the creator: afterwards the group is gone for them.
        assertEquals("left the group", ok(fay, "group-leave", "--group", group))
        refused(fay, "group-status", "--group", group)
        assertEquals("committed at seq 5, epoch 3", ok(eve, "group-remove", "--group", group, "--device", gusId))
        refused(gus, "group-fetch", "--group", group)
        assertEquals("committed at seq 6, epoch 4", ok(eve, "group-remove", "--group", group, "--device", fayId), "a ghost may be named")
        // Gus was removed, so it has no group to post to.
        refused(gus, "group-post", "--group", group)
    }

    /**
     * Real MLS groups through the app's sync engine (#22, ADR-0016) with the server and the CLI as separate processes: a
     * group is created from a claimed KeyPackage, the member joins from its welcome, messages are encrypted, read and
     * acknowledged, a third member is added and removed (the removed device finds the group gone), and a commit the server
     * refuses (only the creator may remove another member) leaves the group working. The texts the test sends must appear
     * nowhere in the server's, Migrate's or Admin's output or in Postgres' log. Fresh accounts: a device may log in 10 times an hour.
     */
    @Suppress("LongMethod") // One scripted scenario with three accounts; splitting it would hide the order that matters.
    private fun chat(
        cli: Cli,
        dbEnv: Map<String, String>,
    ) {
        fun register(name: String): Path {
            val state = work.resolve("state-$name")
            assertEquals(0, cli.run("register", state, "--username", "e2e_$name", stdin = createInvite(dbEnv)).exit, "register $name")
            return state
        }

        fun ok(
            state: Path,
            command: String,
            vararg args: String,
            stdin: String = "",
        ): List<String> {
            val finished = cli.run(command, state, *args, stdin = stdin)
            assertEquals(0, finished.exit, "$command exit code")
            return finished.stdout.lines().filter { it.isNotBlank() }
        }

        fun refused(
            state: Path,
            command: String,
            exit: Int,
            message: String,
            vararg args: String,
            stdin: String = "",
        ) {
            val finished = cli.run(command, state, *args, stdin = stdin)
            assertEquals(exit, finished.exit, "$command exit code")
            assertTrue("gifitup-cli: $message" in finished.stderr, "$command: the closed message")
        }

        fun say(text: String): String = text.also { plaintexts += it }
        // The GIF the CLI sends is content too: its slug and link stay inside the encryption (ADR-0003).
        plaintexts += listOf("e2e-test-gif", "0123456789abcdef0123456789abcdef")
        val iris = register("iris")
        val jack = register("jack")
        val kim = register("kim")
        assertEquals(0, cli.run("keypackages-refill", jack).exit, "jack refills")
        assertEquals(0, cli.run("keypackages-refill", kim).exit, "kim refills")

        val created = ok(iris, "chat-create", "--target", "e2e_jack").single()
        val group = Regex("^created group (\\S+) at seq 1$").matchEntire(created)?.groupValues?.get(1) ?: fail("chat-create: $created")
        refused(iris, "chat-send", EXIT_REFUSED, "this device is not in that group", "--group", "AQIDBAUGBwgJCgsMDQ4PEA")
        refused(
            iris,
            "chat-remove",
            EXIT_REFUSED,
            "this device is not in that group",
            "--group",
            "AQIDBAUGBwgJCgsMDQ4PEA",
            "--target",
            "e2e_jack",
        )

        assertEquals(listOf("joined group $group"), ok(jack, "chat-welcomes"))
        assertEquals(
            listOf("group $group active with e2e_jack"),
            ok(iris, "chat-groups"),
            "the creator lists the group with the other member",
        )
        assertEquals(listOf("group $group active with e2e_iris"), ok(jack, "chat-groups"), "the joiner too")
        assertEquals(listOf("no groups"), ok(kim, "chat-groups"))
        refused(iris, "chat-send", EXIT_REFUSED, "that can't be done", "--group", group, stdin = "x".repeat(121))
        assertEquals(listOf("sent at seq 2"), ok(iris, "chat-send", "--group", group, stdin = say("hello jack")))
        assertEquals(
            listOf("message 2 from e2e_iris: gif e2e-test-gif \"hello jack\"", "synced, 1 log entries"),
            ok(jack, "chat-sync", "--group", group),
            "jack reads the message after its join point only",
        )
        assertEquals(listOf("sent at seq 3"), ok(jack, "chat-send", "--group", group, stdin = say("hi iris")))

        // Adding a third member: iris first reads what jack sent (it is shown), then commits; her own entries are skipped.
        val added = ok(iris, "chat-add", "--group", group, "--target", "e2e_kim")
        assertEquals(
            listOf("message 3 from e2e_jack: gif e2e-test-gif \"hi iris\"", "added the devices of e2e_kim, commit at seq 4"),
            added,
        )
        assertEquals(listOf("joined group $group"), ok(kim, "chat-welcomes"))
        assertEquals(listOf("sent at seq 5"), ok(kim, "chat-send", "--group", group, stdin = say("hey all")))
        refused(kim, "chat-remove", EXIT_REFUSED, "that can't be done", "--group", group, "--target", "e2e_nobody")
        assertEquals(
            listOf("message 5 from e2e_kim: gif e2e-test-gif \"hey all\"", "synced, 3 log entries"),
            ok(jack, "chat-sync", "--group", group),
            "jack applies the add commit, skips his own message and reads kim's",
        )
        assertEquals(
            listOf("message 5 from e2e_kim: gif e2e-test-gif \"hey all\"", "synced, 2 log entries"),
            ok(iris, "chat-sync", "--group", group),
            "iris skips her own commit and reads kim's message",
        )

        // Only the creator may remove another member: the server refuses jack's commit and the group goes on.
        assertTrue(CLAIMED_USABLE_ANY.matches(ok(jack, "keypackages-claim", "--target", "e2e_kim").single()), "jack pins kim")
        refused(
            jack,
            "chat-remove",
            EXIT_SERVER,
            "the server refused it: the server refused the envelope (a rule of the group was broken)",
            "--group",
            group,
            "--target",
            "e2e_kim",
        )
        assertEquals(
            listOf("removed the devices of e2e_kim, commit at seq 6"),
            ok(iris, "chat-remove", "--group", group, "--target", "e2e_kim"),
        )
        assertEquals(listOf("sent at seq 7"), ok(iris, "chat-send", "--group", group, stdin = say("after removal")))
        assertEquals(
            listOf("message 7 from e2e_iris: gif e2e-test-gif \"after removal\"", "synced, 2 log entries"),
            ok(jack, "chat-sync", "--group", group),
            "jack's refused commit left nothing pending: he applies the removal and reads the next message",
        )
        refused(kim, "chat-sync", EXIT_REFUSED, "this group is no longer available", "--group", group)
        assertTrue(ok(kim, "chat-groups").single().startsWith("group $group removed"), "a removed member still lists the group, as removed")

        // A group name is a message too (#217): it is sent after the members are in, and the others read it.
        assertEquals(listOf("renamed at seq 8"), ok(iris, "chat-rename", "--group", group, stdin = say("Weekend")))
        assertEquals(
            listOf("message 8 from e2e_iris: group name \"Weekend\"", "synced, 1 log entries"),
            ok(jack, "chat-sync", "--group", group),
            "jack reads the name",
        )
    }

    /**
     * The GIF provider's key (ADR-0003, #40): an authenticated device gets it (the CLI prints only its length), a call
     * without a session is refused with nothing of it, and it never shows in any process output (the key is in `secrets`).
     */
    private fun gifProvider(
        cli: Cli,
        dbEnv: Map<String, String>,
        port: Int,
        gifKey: String,
    ) {
        val nina = work.resolve("state-nina")
        assertEquals(0, cli.run("register", nina, "--username", "e2e_nina", stdin = createInvite(dbEnv)).exit, "register nina")
        val fetched = cli.run("gif-provider", nina)
        assertEquals(0, fetched.exit, "gif-provider exit code")
        assertEquals(listOf("gif provider klipy, key of ${gifKey.length} characters"), fetched.stdout.lines().filter { it.isNotBlank() })
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { http ->
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/config/gif-provider")).header("GifItUp-Protocol", "1").build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(HTTP_UNAUTHORIZED, response.statusCode(), "no session: refused")
            assertFalse(gifKey in response.body(), "and the refusal carries no key")
        }
    }

    /**
     * The sealed history of the CLI (#177 PR 4c, ADR-0017) with the real cipher: what a device sends and reads is kept in
     * records on disk that hold no plaintext, a later process shows it again, and a history key that is missing or damaged
     * refuses instead of being replaced. Fresh accounts (a device may log in 10 times an hour, so the step is lean).
     */
    @Suppress("LongMethod") // One scripted scenario with two accounts; splitting it would hide the order that matters.
    private fun history(
        cli: Cli,
        dbEnv: Map<String, String>,
    ) {
        fun register(name: String): Path {
            val state = work.resolve("state-$name")
            assertEquals(0, cli.run("register", state, "--username", "e2e_$name", stdin = createInvite(dbEnv)).exit, "register $name")
            return state
        }

        fun ok(
            state: Path,
            command: String,
            vararg args: String,
            stdin: String = "",
        ): List<String> {
            val finished = cli.run(command, state, *args, stdin = stdin)
            assertEquals(0, finished.exit, "$command exit code")
            return finished.stdout.lines().filter { it.isNotBlank() }
        }

        val lena = register("lena")
        val milo = register("milo")
        assertEquals(0, cli.run("keypackages-refill", milo).exit, "milo refills")
        plaintexts += "history one"
        val created = ok(lena, "chat-create", "--target", "e2e_milo").single()
        val group = Regex("^created group (\\S+) at seq 1$").matchEntire(created)?.groupValues?.get(1) ?: fail("chat-create: $created")
        assertEquals(listOf("joined group $group"), ok(milo, "chat-welcomes"))
        assertEquals(listOf("sent at seq 2"), ok(lena, "chat-send", "--group", group, stdin = "history one"))
        val gif = "gif e2e-test-gif \"history one\""
        assertEquals(
            listOf("stored 2 - sent: $gif"),
            ok(lena, "chat-history", "--group", group),
            "the sender keeps its own message as sent",
        )
        assertEquals(
            listOf("message 2 from e2e_lena: $gif", "synced, 1 log entries"),
            ok(milo, "chat-sync", "--group", group),
        )
        assertEquals(
            listOf("synced, 0 log entries"),
            ok(milo, "chat-sync", "--group", group),
            "nothing is read twice",
        )
        val shown = listOf("stored 2 e2e_lena -: $gif")
        assertEquals(shown, ok(milo, "chat-history", "--group", group), "a later process shows the message once")

        // On disk: records with keyed names and no plaintext; the history key is a secret like the others.
        val keyFile = milo.resolve("history-key")
        val key = Files.readAllBytes(keyFile)
        assertEquals(32, key.size)
        secrets +=
            listOf(
                java.util.Base64
                    .getEncoder()
                    .encodeToString(key),
                key.joinToString("") { "%02x".format(it) },
            )
        val records = Files.walk(milo.resolve("history")).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        assertTrue(records.isNotEmpty(), "milo's history has records")
        records.forEach { record ->
            assertTrue(Regex("^[0-9a-f]{64}$").matches(record.name), "a record name is 64 hex digits")
            val bytes = Files.readAllBytes(record).toString(Charsets.ISO_8859_1)
            listOf("history one", "e2e-test-gif", "e2e_lena").forEach { assertFalse(it in bytes, "a record holds plaintext") }
        }

        // A key that is damaged or gone is refused, never replaced; a valid key of another history opens an empty one.
        fun refusedKey(
            expected: ByteArray?,
            what: String,
        ) {
            val finished = cli.run("chat-history", milo, "--group", group)
            assertEquals(EXIT_REFUSED, finished.exit, "$what: exit code")
            assertTrue("gifitup-cli: the history can't be opened (key)" in finished.stderr, "$what: the closed message")
            if (expected == null) {
                assertFalse(Files.exists(keyFile), "$what: no key was made")
            } else {
                assertTrue(expected.contentEquals(Files.readAllBytes(keyFile)), "$what: the key file is untouched")
            }
        }
        Files.delete(keyFile)
        refusedKey(null, "key missing, records present")
        val damaged = ByteArray(31) { 9 }
        Files.write(keyFile, damaged)
        refusedKey(damaged, "key of the wrong size")
        val other = ByteArray(32) { 5 }
        Files.write(keyFile, other)
        val wrong = ok(milo, "chat-history", "--group", group)
        assertTrue(wrong.none { it.startsWith("stored ") }, "another key shows none of the old messages")
        assertTrue(other.contentEquals(Files.readAllBytes(keyFile)), "and the key file is untouched")
        Files.write(keyFile, key)
        assertEquals(shown, ok(milo, "chat-history", "--group", group), "the right key shows the message again: nothing was lost")
    }

    /**
     * Trust on first use (#18, ADR-0014): a claim pins the key; the admin deletes the account and the name is registered
     * again (a new account id); the next claim is blocked as a reassigned username, the old account is gone, and the
     * blind dev-CLI accept lets the claim through; a damaged contact book refuses claims. Fresh accounts, so the
     * login limit of the earlier steps doesn't apply.
     */
    private fun trust(
        cli: Cli,
        dbEnv: Map<String, String>,
    ) {
        val gina = work.resolve("state-gina")
        val hank = work.resolve("state-hank")
        val gina2 = work.resolve("state-gina2")
        assertEquals(0, cli.run("register", gina, "--username", "e2e_gina", stdin = createInvite(dbEnv)).exit, "register gina")
        assertEquals(0, cli.run("keypackages-refill", gina).exit, "gina refills")
        val hankLink = createdLink(cli, gina, "link-hank")
        assertEquals(0, cli.run("register", hank, "--username", "e2e_hank", stdin = hankLink).exit, "register hank")

        val first = cli.run("keypackages-claim", hank, "--target", "e2e_gina")
        assertEquals(0, first.exit, "the first claim: exit code")
        assertTrue(claimedUsable("e2e_gina").matches(first.stdout.trim()), "the first claim pins gina")

        val env = dbEnv + ("GIFITUP_PUBLIC_URL" to ORIGIN)
        val deleted = processes.run("admin", serverEntryPoint("AdminKt") + listOf("delete-account", "e2e_gina"), env, excludeStdout = true)
        assertEquals(0, deleted.exit, "Admin delete-account")
        assertEquals(0, cli.run("register", gina2, "--username", "e2e_gina", stdin = createInvite(dbEnv)).exit, "register gina again")
        assertEquals(0, cli.run("keypackages-refill", gina2).exit, "the new gina refills")

        val blocked = cli.run("keypackages-claim", hank, "--target", "e2e_gina")
        assertEquals(EXIT_REFUSED, blocked.exit, "a reassigned name: exit code")
        assertTrue("gifitup-cli: blocked" in blocked.stderr, "a reassigned name: blocked")
        val book =
            cli
                .run("contacts", hank)
                .stdout
                .lines()
                .filter { it.isNotBlank() }
        assertTrue(book.all(CONTACT_LINE::matches), "contacts lines: $book")
        val old = book.filter { "  e2e_gina  gone  " in it }
        val reassigned = book.filter { "  e2e_gina  blocked  " in it && it.endsWith("  username_reassigned") }
        assertEquals(1, old.size, "the old gina is gone, its pins kept")
        assertEquals(1, reassigned.size, "the new gina is blocked as a reassigned username")

        val accept = cli.run("contacts-accept", hank, "--account", reassigned.single().substringBefore(' '))
        assertEquals(0, accept.exit, "contacts-accept: exit code")
        assertEquals("accepted e2e_gina blindly (test accounts only)", accept.stdout.trim())
        assertEquals(0, cli.run("keypackages-claim", hank, "--target", "e2e_gina").exit, "after the accept the claim passes")

        // A damaged book is unavailable, never empty: the claim is refused and nothing is pinned again.
        Files.write(hank.resolve("contacts"), byteArrayOf(1, 2, 3))
        val unavailable = cli.run("keypackages-claim", hank, "--target", "e2e_gina")
        assertEquals(EXIT_REFUSED, unavailable.exit, "a damaged book: exit code")
        assertTrue("gifitup-cli: the state dir's contact book" in unavailable.stderr, "a damaged book: refused")
    }

    /** `invite-create` into [fileName] in the work dir; returns the link, whose code then counts as a secret. */
    private fun createdLink(
        cli: Cli,
        state: Path,
        fileName: String,
    ): String {
        val file = work.resolve(fileName)
        val created = cli.run("invite-create", state, "--link-file", "$file")
        assertEquals(0, created.exit, "invite-create exit code")
        assertTrue(CREATED_LINE.matches(created.stdout.trim()), "invite-create prints the id and the file, never the code")
        val link = Files.readString(file).trim()
        assertTrue(link.startsWith("$ORIGIN/invite#"), "the link file holds a link to the configured origin")
        secrets += link.substringAfterLast('#')
        return link
    }

    /** The id of the one listed invite in [status]. */
    private fun inviteId(
        listed: String,
        status: String,
    ): String =
        listed
            .lines()
            .singleOrNull { it.contains("  $status  ") }
            ?.substringBefore(' ')
            ?: fail("invites lists no single $status invite")

    private fun assertNoSecretInOutput() {
        val processOutput = processes.capturedOutput()
        (processOutput + ("postgres" to EndToEndDatabase.serverLog())).forEach { (name, text) ->
            secrets.forEachIndexed { i, secret -> assertFalse(secret in text, "secret #$i appears in the $name output") }
        }
        // What the chat step sent was encrypted before it left the CLI: no process but the CLI itself may have seen it.
        (processOutput + ("postgres" to EndToEndDatabase.serverLog()))
            .filterNot { (name, _) -> name.startsWith("cli ") }
            .forEach { (name, text) ->
                plaintexts.forEach { plaintext -> assertFalse(plaintext in text, "a chat text appears in the $name output") }
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
        const val HTTP_UNAUTHORIZED = 401
        const val POLL_MILLIS = 200L
        const val EXIT_SERVER = 1
        const val EXIT_REFUSED = 3
        const val MAX_OPEN_INVITES = 5
        const val UUID_LENGTH = 36

        fun claimedUsable(name: String) = Regex("^[0-9a-f-]{36} {2}crate-valid, key pinned \\(credential $name\\)$")

        val CLAIMED_USABLE_ANY = Regex("^[0-9a-f-]{36} {2}crate-valid, key pinned \\(credential [a-z0-9_]+\\)$")
        val CLAIMED_EMPTY = Regex("^[0-9a-f-]{36} {2}empty \\(no KeyPackage left\\)$")
        val CREATED_LINE = Regex("^invite [0-9a-f-]{36} created, expires \\S+; link in \\S+$")
        val CONTACT_LINE = Regex("^[0-9a-f-]{36}  [a-z0-9_]+  (ok|unconfirmed|blocked|gone)  devices \\d+(  [a-z_]+)?$")
        val DEVICE_LINE = Regex("^registered e2e_ann as device ([0-9a-f-]{36})$")
    }
}
