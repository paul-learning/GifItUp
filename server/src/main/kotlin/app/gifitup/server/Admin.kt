package app.gifitup.server

import app.gifitup.server.auth.CanonicalOrigin
import app.gifitup.server.auth.InviteCodes
import app.gifitup.server.auth.UsernamePolicy
import app.gifitup.server.db.AccountRemoval
import app.gifitup.server.db.Database
import app.gifitup.server.db.DatabaseConfig
import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.NewInvite
import app.gifitup.server.db.TransactionRunner
import java.io.PrintStream
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.system.exitProcess

/**
 * The admin CLI (ADR-0010), run on the VM as a one-shot compose service with the restricted app role:
 *
 * ```
 * docker compose --profile admin run --rm admin create-invite
 * docker compose --profile admin run --rm admin delete-account <username>
 * docker compose --profile admin run --rm admin delete-account --with-invitees <username>
 * ```
 *
 * The invite link is written once, straight to stdout, never through the logger; the `admin` service has Docker
 * logging off and runs with `--rm`, so the link isn't kept on disk (#13 SEC-47). Errors are fixed messages.
 */
fun main(args: Array<String>) {
    val env = System.getenv()
    val exit =
        runCatching {
            val origin =
                CanonicalOrigin.of(env["GIFITUP_PUBLIC_URL"].orEmpty()) ?: throw AdminError("GIFITUP_PUBLIC_URL must be set and canonical")
            Database.connect(DatabaseConfig.fromEnv(env)).use { db ->
                AdminCommands(db, origin, Clock.systemUTC(), SecureRandom()).run(args.toList(), System.out)
            }
        }.getOrElse { error ->
            // Only our own fixed messages are shown; anything else by class (SQL messages can quote data).
            System.err.println("admin: " + if (error is AdminError) error.message else error::class.java.name)
            // A retry can help only with a database failure, and only if no result was printed (#74 SEC-62).
            if (error !is AdminError) {
                System.err.println("admin: if no result was printed above, nothing was changed; run the command again")
            }
            EXIT_FAILURE
        }
    exitProcess(exit)
}

/** The commands, separate from `main` so tests can run them against a test database. Returns a process exit code. */
class AdminCommands(
    private val db: TransactionRunner,
    private val origin: CanonicalOrigin,
    private val clock: Clock,
    private val random: SecureRandom,
) {
    private val invites = InviteRepository()
    private val removal = AccountRemoval()

    fun run(
        args: List<String>,
        out: PrintStream,
    ): Int =
        when {
            args == listOf("create-invite") -> {
                createInvite(out)
            }

            args.size == DELETE_ARGS && args[0] == "delete-account" && !args[1].startsWith("--") -> {
                deleteAccount(args[1], withInvitees = false, out)
            }

            args.size == DELETE_WITH_INVITEES_ARGS && args[0] == "delete-account" && args[1] == "--with-invitees" -> {
                deleteAccount(args[2], withInvitees = true, out)
            }

            else -> {
                out.println(USAGE)
                EXIT_USAGE
            }
        }

    /** An admin invite (no inviter, bootstrap root), valid for [ADMIN_INVITE_LIFETIME] (#13 SEC-47). */
    private fun createInvite(out: PrintStream): Int {
        val code = InviteCodes.generate(random)
        val now = clock.instant()
        val expiresAt = now.plus(ADMIN_INVITE_LIFETIME)
        db.transaction { c -> invites.insert(c, NewInvite(UUID.randomUUID(), checkNotNull(InviteCodes.hash(code)), null, now, expiresAt)) }
        out.println(InviteCodes.link(origin, code))
        out.println("valid until $expiresAt (UTC), single use")
        return EXIT_OK
    }

    private fun deleteAccount(
        username: String,
        withInvitees: Boolean,
        out: PrintStream,
    ): Int {
        // Validated before it reaches the database (#69 SEC-23); the rule is public, so echoing it is fine.
        if (!UsernamePolicy.isValid(username)) throw AdminError("not a valid username")
        val deleted =
            db.transaction { c ->
                if (withInvitees) removal.deleteWithInvitees(c, username) else removal.deleteAccount(c, username)
            }
        out.println("deleted $deleted account(s)")
        return if (deleted > 0) EXIT_OK else EXIT_FAILURE
    }

    companion object {
        val ADMIN_INVITE_LIFETIME: Duration = Duration.ofHours(24)
        private const val DELETE_ARGS = 2
        private const val DELETE_WITH_INVITEES_ARGS = 3
        private const val USAGE =
            "usage: create-invite | delete-account <username> | delete-account --with-invitees <username>"
    }
}

/** An admin error with a fixed message that is safe to print. */
class AdminError(
    override val message: String,
) : Exception(message)

private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
