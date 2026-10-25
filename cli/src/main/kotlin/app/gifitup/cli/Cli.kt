package app.gifitup.cli

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.RestoreCounter
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.auth.ListDevices
import app.gifitup.shared.domain.auth.LogIn
import app.gifitup.shared.domain.auth.LogOut
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.auth.TestAccountGuard
import app.gifitup.shared.domain.contacts.AcceptContact
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.RefreshContacts
import app.gifitup.shared.domain.keypackages.ClaimKeyPackages
import app.gifitup.shared.domain.keypackages.RefillKeyPackages
import io.ktor.client.engine.HttpClientEngine
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant

/** The parsed command line. Every option at most once; unknown or misplaced options are a usage error. */
data class Invocation(
    val command: String,
    val server: String,
    val stateDir: Path,
    val origin: String?,
    val username: String?,
    val remoteTestAccount: Boolean,
    val target: String? = null,
    val linkFile: Path? = null,
    val inviteId: String? = null,
    val account: String? = null,
    val group: ByteArray? = null,
    val device: String? = null,
    val text: String? = null,
    val seq: Long? = null,
    val welcome: String? = null,
) {
    companion object {
        /** The Delivery Service commands (test driver, ADR-0015): required and optional options of each. */
        private val GROUP_COMMANDS =
            mapOf(
                "group-create" to (setOf("--device") to emptySet()),
                "group-add" to (setOf("--group", "--device") to emptySet()),
                "group-remove" to (setOf("--group", "--device") to emptySet()),
                "group-post" to (setOf("--group", "--text") to emptySet()),
                "group-fetch" to (setOf("--group") to setOf("--seq")),
                "group-status" to (setOf("--group") to emptySet()),
                "group-ack" to (setOf("--group", "--seq") to emptySet()),
                "group-leave" to (setOf("--group") to emptySet()),
                "welcomes" to (emptySet<String>() to emptySet()),
                "welcome-ack" to (setOf("--welcome") to emptySet()),
            )
        private val COMMANDS =
            setOf(
                "register",
                "login",
                "devices",
                "reset",
                "keypackages-refill",
                "keypackages-status",
                "keypackages-claim",
                "invite-create",
                "invites",
                "invite-revoke",
                "contacts",
                "contacts-accept",
            ) + GROUP_COMMANDS.keys
        private val VALUE_OPTIONS =
            setOf(
                "--server",
                "--state-dir",
                "--origin",
                "--username",
                "--target",
                "--link-file",
                "--id",
                "--account",
                "--group",
                "--device",
                "--text",
                "--seq",
                "--welcome",
            )

        private val GROUP_OPTIONS = setOf("--group", "--device", "--text", "--seq", "--welcome")
        private const val MAX_TEXT_CHARS = 500
        private const val MIN_GROUP_ID = 16
        private const val MAX_GROUP_ID = 64

        /** The options that one command needs and no other takes. */
        private val COMMAND_OPTIONS =
            mapOf(
                "register" to "--username",
                "keypackages-claim" to "--target",
                "invite-create" to "--link-file",
                "invite-revoke" to "--id",
                "contacts-accept" to "--account",
            )
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun parse(args: List<String>): Invocation {
            val command = args.firstOrNull()?.takeIf { it in COMMANDS } ?: usage()
            val values = mutableMapOf<String, String>()
            var remote = false
            var i = 1
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == "--remote-test-account" && !remote -> remote = true
                    arg in VALUE_OPTIONS && arg !in values && i + 1 < args.size -> values[arg] = args[++i]
                    else -> usage()
                }
                i++
            }
            if (COMMAND_OPTIONS.any { (owner, option) -> (command == owner) != (option in values) }) usage()
            if (!idsAreCanonical(values) || !groupOptionsFit(command, values)) usage()
            return invocation(command, values, remote)
        }

        private fun invocation(
            command: String,
            values: Map<String, String>,
            remote: Boolean,
        ): Invocation =
            Invocation(
                command = command,
                server = values["--server"] ?: usage(),
                stateDir = Path.of(values["--state-dir"] ?: usage()),
                origin = values["--origin"],
                username = values["--username"],
                remoteTestAccount = remote,
                target = values["--target"],
                linkFile = values["--link-file"]?.let { Path.of(it) },
                inviteId = values["--id"],
                account = values["--account"],
                group = values["--group"]?.let(::groupIdOf),
                device = values["--device"],
                text = values["--text"],
                seq = values["--seq"]?.toLong(),
                welcome = values["--welcome"],
            )

        private fun idsAreCanonical(values: Map<String, String>): Boolean =
            listOf("--id", "--account", "--device", "--welcome").all { option -> values[option]?.let(UUID::matches) != false }

        /** Only the group commands take the group options, each takes exactly its own, and the values have their shapes. */
        private fun groupOptionsFit(
            command: String,
            values: Map<String, String>,
        ): Boolean {
            val given = values.keys.filter { it in GROUP_OPTIONS }.toSet()
            val (required, optional) = GROUP_COMMANDS[command] ?: (emptySet<String>() to emptySet())
            val shapes =
                (values["--group"]?.let { groupIdOf(it) != null } != false) &&
                    (values["--seq"]?.let { it.length in 1..SEQ_DIGITS && it.all(Char::isDigit) } != false) &&
                    (values["--text"]?.let { it.isNotEmpty() && it.length <= MAX_TEXT_CHARS } != false)
            return given.containsAll(required) && (given - required).all { it in optional } && shapes
        }

        /** A group id as printed by the CLI: base64url, 16 to 64 bytes. */
        private fun groupIdOf(text: String): ByteArray? =
            runCatching {
                java.util.Base64
                    .getUrlDecoder()
                    .decode(text)
            }.getOrNull()?.takeIf { it.size in MIN_GROUP_ID..MAX_GROUP_ID }

        private const val SEQ_DIGITS = 15

        private fun usage(): Nothing = throw CliError(USAGE, EXIT_USAGE)

        const val USAGE =
            """usage: gifitup-cli <command> --server <url> --state-dir <dir> [--origin <https origin>] [--remote-test-account]
  register --username <name>   register this state dir's device with an invite (the code or link, one line on stdin;
                               typed input is echoed, so pipe it in where others can see the screen)
  login                        log in and out again (checks the device still works)
  devices                      list the account's devices
  reset                        discard a pending registration (never a registered one)
  keypackages-refill           fill this device's KeyPackage pool on the server if it is below the server's mark
  keypackages-status           how many KeyPackages the server holds for this device
  keypackages-claim --target <name>   claim one KeyPackage per device of another member (consumes them; never repeated)
  invite-create --link-file <file>    create an invite; its link goes to <file> (new, owner-only), never to the screen
  invites                      list this account's invites
  invite-revoke --id <invite id>      revoke one of this account's open invites
  contacts                     refresh the contact book from the server's member list and print it
  contacts-accept --account <account id>   TEST ACCOUNTS ONLY: accept a blocked contact's new key blindly, without any check
  group-create --device <device id>   Delivery Service test driver (random bytes as payloads): new group adding that device
  group-add|group-remove --group <id> --device <device id>   a commit at the group's current epoch
  group-post --group <id> --text <text>      an application message (the text is the payload)
  group-fetch --group <id> [--seq <after>]   messages above a cursor; group-status --group <id>
  group-ack --group <id> --seq <n>           acknowledge up to n; group-leave --group <id>
  welcomes                     pending welcomes of this device; welcome-ack --welcome <id>
--server is https://<origin>, or http://127.0.0.1:<port> / http://[::1]:<port> together with --origin.
Only test origins (*.invalid, *.test, localhost) unless --remote-test-account and a cli_ username are given.
Every command logs in and out again; the server allows 10 logins per device per hour."""
    }
}

/**
 * The commands, over the same use cases the app uses: argument handling, the state dir and printing only (CLAUDE.md
 * "Dev CLI": no client logic of its own). Output is fixed text per result code; tokens, keys and invite codes are never
 * printed. Returns the process exit code.
 */
class Cli(
    private val engine: () -> HttpClientEngine,
    private val mls: MlsClientFactory,
    private val random: SecureRandomSource,
    private val readInvite: () -> String,
    private val out: PrintStream,
    private val err: PrintStream,
) {
    suspend fun run(invocation: Invocation): Int {
        val endpoint = Connection.endpoint(invocation.server, invocation.origin)
        val state = StateDir(invocation.stateDir)
        // GifItUpApi.close() leaves the engine to its owner (#94 CR-6), so both are closed here.
        return state.lock().use {
            engine().use { engine ->
                GifItUpApi(engine, endpoint).use { api -> dispatch(invocation, api, state) }
            }
        }
    }

    private suspend fun dispatch(
        invocation: Invocation,
        api: GifItUpApi,
        state: StateDir,
    ): Int {
        val register = RegisterDevice(api, guarded(), state.identities, state.keys, random, lock)
        return when (invocation.command) {
            "register" -> register(invocation, api, register)
            "reset" -> report(register.discardPending()) { out.println("pending registration discarded") }
            else -> loggedIn(invocation, api, state) { session -> afterLogin(invocation, api, state, session) }
        }
    }

    private suspend fun register(
        invocation: Invocation,
        api: GifItUpApi,
        register: RegisterDevice,
    ): Int {
        val username = checkNotNull(invocation.username)
        guard(api, username, invocation.remoteTestAccount)
        return report(register(username, inviteCodeFrom(readInvite()))) { session ->
            out.println("registered $username as device ${session.deviceId}")
            logOut(api, session)
        }
    }

    private suspend fun loggedIn(
        invocation: Invocation,
        api: GifItUpApi,
        state: StateDir,
        then: suspend (Session) -> Int,
    ): Int {
        val stored = state.identities.load()
        if (stored != null) guard(api, stored.username, invocation.remoteTestAccount)
        // A claim consumes the target's KeyPackages and spends their budget: a real member is off limits too (#123 CR-2).
        invocation.target?.let { guard(api, it, invocation.remoteTestAccount) }
        val login = LogIn(api, guarded(), state.identities, state.keys, lock)()
        if (login !is AuthResult.Ok) return report(login) { }
        return try {
            then(login.value)
        } finally {
            logOut(api, login.value)
        }
    }

    /** A failed logout doesn't change the result: the token is dropped with the process and expires on its own (#103 CR-2). */
    private suspend fun logOut(
        api: GifItUpApi,
        session: Session,
    ) {
        if (LogOut(api)(session) !is AuthResult.Ok) {
            err.println("gifitup-cli: warning: logout failed; the session expires at ${Instant.ofEpochSecond(session.expiresAt)}")
        }
    }

    private suspend fun afterLogin(
        invocation: Invocation,
        api: GifItUpApi,
        state: StateDir,
        session: Session,
    ): Int {
        val mls = guarded()
        val refill = RefillKeyPackages(api, mls, state.identities, state.keys, lock)
        val access = ContactBookAccess(state.contacts) { Instant.now().epochSecond }
        val claim = ClaimKeyPackages(api, mls, state.identities, state.keys, lock, access)
        val keyPackages = KeyPackageCommands(api, refill, claim, out) { report(it) { } }
        val invites = InviteCommands(api, out) { report(it) { } }
        val contacts =
            ContactCommands(RefreshContacts(api, mls, state.identities, state.keys, lock, access), AcceptContact(access), access, out) {
                report(it) { }
            }
        return when (invocation.command) {
            "keypackages-refill" -> {
                keyPackages.refill(session)
            }

            "keypackages-status" -> {
                keyPackages.status(session)
            }

            "keypackages-claim" -> {
                keyPackages.claim(session, checkNotNull(invocation.target))
            }

            "invite-create" -> {
                invites.create(session, checkNotNull(invocation.linkFile))
            }

            "invites" -> {
                invites.list(session)
            }

            "invite-revoke" -> {
                invites.revoke(session, checkNotNull(invocation.inviteId))
            }

            "contacts" -> {
                contacts.list(session)
            }

            "contacts-accept" -> {
                contacts.accept(checkNotNull(invocation.account))
            }

            in DELIVERY_COMMANDS -> {
                DeliveryCommands(api, random, out) {
                    throw CliError(message(AuthError.Api(it)), EXIT_SERVER)
                }.run(invocation, session)
            }

            else -> {
                deviceCommand(invocation.command, api, session)
            }
        }
    }

    private suspend fun deviceCommand(
        command: String,
        api: GifItUpApi,
        session: Session,
    ): Int =
        when (command) {
            "login" -> {
                out.println("logged in as device ${session.deviceId}, session valid until ${Instant.ofEpochSecond(session.expiresAt)}")
                EXIT_OK
            }

            else -> {
                report(ListDevices(api)(session)) { devices ->
                    devices.forEach {
                        val created = Instant.ofEpochSecond(it.createdAt)
                        val lastSeen = Instant.ofEpochSecond(it.lastSeenAt)
                        out.println("${it.deviceId}  created $created  last seen $lastSeen")
                    }
                }
            }
        }

    /** One per command: the state dir's file lock already keeps other processes out. */
    private val lock = DeviceLock()

    /** No stored restore counter: on the JVM a panic comes back as an exception, so there is no reload loop (ADR-0007). */
    private fun guarded() = GuardedMlsFactory(mls, NoRestoreCounter)

    private fun guard(
        api: GifItUpApi,
        username: String,
        remoteTestAccount: Boolean,
    ) {
        when (TestAccountGuard.check(api.endpoint.origin, username, remoteTestAccount)) {
            TestAccountGuard.Refusal.NOT_A_TEST_ORIGIN -> {
                throw CliError("${api.endpoint.origin} is not a test origin; add --remote-test-account (test accounts only)", EXIT_REFUSED)
            }

            TestAccountGuard.Refusal.NOT_A_TEST_USERNAME -> {
                throw CliError("against a real server only ${TestAccountGuard.PREFIX}… usernames (test accounts)", EXIT_REFUSED)
            }

            null -> {}
        }
    }

    private suspend fun <T> report(
        result: AuthResult<T>,
        onOk: suspend (T) -> Unit,
    ): Int =
        when (result) {
            is AuthResult.Ok -> {
                onOk(result.value)
                EXIT_OK
            }

            is AuthResult.Failed -> {
                throw CliError(message(result.error), if (result.error is AuthError.Api) EXIT_SERVER else EXIT_REFUSED)
            }
        }
}

/**
 * A restore counter that counts nothing, for the dev CLI only (#108 CR-4): on the JVM a restore can't trap, so there is
 * no reload loop to break. It lives here, not in `:shared`, so no app composition root can pick it up.
 */
private object NoRestoreCounter : RestoreCounter {
    override suspend fun failures(): Int = 0

    override suspend fun set(failures: Int) = Unit
}

/** The commands of the Delivery Service test driver. */
private val DELIVERY_COMMANDS =
    setOf(
        "group-create",
        "group-add",
        "group-remove",
        "group-post",
        "group-fetch",
        "group-status",
        "group-ack",
        "group-leave",
        "welcomes",
        "welcome-ack",
    )

/** The invite code from what the user pasted: the code itself or the invite link, whose fragment is the code. */
fun inviteCodeFrom(input: String): String = input.trim().substringAfterLast('#')

/** Fixed text per result code: never server text, never a secret (#79 plan, "Output"). */
fun message(error: AuthError): String =
    when (error) {
        is AuthError.Api -> apiMessage(error.error)
        is AuthError.Local -> localMessage(error)
    }

@Suppress("CyclomaticComplexMethod") // One fixed line per closed code.
private fun apiMessage(error: ApiError): String =
    when (error) {
        ApiError.BAD_REQUEST -> "the server refused the request as malformed"
        ApiError.CLIENT_OUTDATED -> "this CLI is too old for the server"
        ApiError.SERVER_OUTDATED -> "the server is older than this CLI"
        ApiError.UNAUTHORIZED -> "not authorized (wrong origin, unknown device, or the challenge expired)"
        ApiError.INVITE_INVALID -> "invite not accepted (unknown, used, revoked or expired)$RESUME_HINT"
        ApiError.USERNAME_UNAVAILABLE -> "username taken or reserved$RESUME_HINT"
        ApiError.RATE_LIMITED -> "rate limited by the server; try again later"
        ApiError.NOT_FOUND -> "no such member"
        ApiError.KEY_PACKAGE_INVALID -> "the server refused a KeyPackage as invalid"
        ApiError.KEY_PACKAGE_LIMIT -> "this device already holds the most KeyPackages the server allows"
        ApiError.INVITE_LIMIT -> "too many open invites; revoke one or wait until one is used or expires"
        ApiError.ENVELOPE_INVALID -> "the server refused the envelope (a rule of the group was broken)"
        ApiError.ENVELOPE_CONFLICT -> "the same message id was sent with a different envelope"
        ApiError.EPOCH_CONFLICT -> "another commit won this epoch; fetch the group and retry"
        ApiError.GROUP_FULL -> "the group's log is full until members acknowledge"
        ApiError.WELCOME_LIMIT -> "too many pending welcomes for that device"
        ApiError.GROUP_LIMIT -> "the device is at its group limit, or was added too often today"
        ApiError.PAYLOAD_TOO_LARGE -> "a payload is over its size cap"
        ApiError.SERVER_ERROR -> "the server failed"
        ApiError.MALFORMED_RESPONSE -> "the server's answer broke the protocol"
        ApiError.NETWORK -> "no answer from the server (network error or timeout)"
    }

@Suppress("CyclomaticComplexMethod") // One fixed line per closed code.
private fun localMessage(error: AuthError.Local): String =
    when (error) {
        AuthError.Local.INVALID_USERNAME -> "the username must be 3-32 of a-z, 0-9 and _"
        AuthError.Local.INVALID_INVITE_CODE -> "that is not an invite code (22 characters) or an invite link"
        AuthError.Local.ALREADY_REGISTERED -> "this state dir already holds a registered device"
        AuthError.Local.NOT_REGISTERED -> "this state dir holds no registered device; run register first"
        AuthError.Local.USERNAME_MISMATCH -> "a pending registration for another username is in this state dir; resume it or run reset"
        AuthError.Local.OTHER_ORIGIN -> "this state dir belongs to another origin"
        AuthError.Local.STATE_INVALID -> "the state dir is damaged or doesn't match its key"
        AuthError.Local.CRYPTO -> "the MLS client failed"
        AuthError.Local.KEY_UNAVAILABLE -> "the state dir's key can't be used"
        AuthError.Local.RESTORE_BLOCKED -> "the last restores failed; this can't happen in the CLI"
        AuthError.Local.STORAGE_FAILED -> "the state dir couldn't be read or written"
        AuthError.Local.CONTACTS_UNAVAILABLE -> "the state dir's contact book is missing, damaged or can't be written; nothing was pinned"
        AuthError.Local.CONTACT_BLOCKED -> "blocked: a key, a name or a device of this contact changed; see contacts"
        AuthError.Local.UNEXPECTED -> "an unexpected error on this device"
    }

/** After a refused resume the identity is kept on purpose (#95 CR-5): only the user knows the earlier attempt is lost. */
private const val RESUME_HINT = "; if this was a retry and the earlier attempt is lost, run reset"
