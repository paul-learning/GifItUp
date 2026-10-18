package app.gifitup.cli

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.ListDevices
import app.gifitup.shared.domain.auth.LogIn
import app.gifitup.shared.domain.auth.LogOut
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.auth.TestAccountGuard
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
) {
    companion object {
        private val COMMANDS = setOf("register", "login", "devices", "reset")
        private val VALUE_OPTIONS = setOf("--server", "--state-dir", "--origin", "--username")

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
            val username = values["--username"]
            if ((command == "register") != (username != null)) usage()
            return Invocation(
                command = command,
                server = values["--server"] ?: usage(),
                stateDir = Path.of(values["--state-dir"] ?: usage()),
                origin = values["--origin"],
                username = username,
                remoteTestAccount = remote,
            )
        }

        private fun usage(): Nothing = throw CliError(USAGE, EXIT_USAGE)

        const val USAGE =
            """usage: gifitup-cli <command> --server <url> --state-dir <dir> [--origin <https origin>] [--remote-test-account]
  register --username <name>   register this state dir's device with an invite (the code or link, one line on stdin;
                               typed input is echoed, so pipe it in where others can see the screen)
  login                        log in and out again (checks the device still works)
  devices                      list the account's devices
  reset                        discard a pending registration (never a registered one)
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
        val register = RegisterDevice(api, mls, state.identities, state.keys, random)
        return when (invocation.command) {
            "register" -> register(invocation, api, register)
            "reset" -> report(register.discardPending()) { out.println("pending registration discarded") }
            else -> loggedIn(invocation, api, state) { session -> afterLogin(invocation.command, api, session) }
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
        val login = LogIn(api, mls, state.identities, state.keys)()
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

/** The invite code from what the user pasted: the code itself or the invite link, whose fragment is the code. */
fun inviteCodeFrom(input: String): String = input.trim().substringAfterLast('#')

/** Fixed text per result code: never server text, never a secret (#79 plan, "Output"). */
fun message(error: AuthError): String =
    when (error) {
        is AuthError.Api -> apiMessage(error.error)
        is AuthError.Local -> localMessage(error)
    }

private fun apiMessage(error: ApiError): String =
    when (error) {
        ApiError.BAD_REQUEST -> "the server refused the request as malformed"
        ApiError.CLIENT_OUTDATED -> "this CLI is too old for the server"
        ApiError.SERVER_OUTDATED -> "the server is older than this CLI"
        ApiError.UNAUTHORIZED -> "not authorized (wrong origin, unknown device, or the challenge expired)"
        ApiError.INVITE_INVALID -> "invite not accepted (unknown, used, revoked or expired)$RESUME_HINT"
        ApiError.USERNAME_UNAVAILABLE -> "username taken or reserved$RESUME_HINT"
        ApiError.RATE_LIMITED -> "rate limited by the server; try again later"
        ApiError.SERVER_ERROR -> "the server failed"
        ApiError.MALFORMED_RESPONSE -> "the server's answer broke the protocol"
        ApiError.NETWORK -> "no answer from the server (network error or timeout)"
    }

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
    }

/** After a refused resume the identity is kept on purpose (#95 CR-5): only the user knows the earlier attempt is lost. */
private const val RESUME_HINT = "; if this was a retry and the earlier attempt is lost, run reset"
