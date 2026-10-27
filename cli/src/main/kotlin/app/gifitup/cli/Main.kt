package app.gifitup.cli

import app.gifitup.mls.UniffiMlsClientFactory
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * Entry point of the dev CLI (#79). Errors are fixed messages; anything unexpected is printed by class name only,
 * never with its message or a stack trace, which could quote a token or a key (#79 plan SEC-3).
 */
fun main(args: Array<String>) {
    val random = SecureRandom()
    val exit =
        runCatching {
            val cli =
                Cli(
                    engine = { Java.create { protocolVersion = HttpClient.Version.HTTP_1_1 } },
                    mls = UniffiMlsClientFactory,
                    random = { size -> ByteArray(size).also(random::nextBytes) },
                    readInvite = ::readInvite,
                    readText = ::readText,
                    out = System.out,
                    err = System.err,
                )
            runBlocking { cli.run(Invocation.parse(args.toList())) }
        }.getOrElse { error ->
            System.err.println("gifitup-cli: " + if (error is CliError) error.message else "failed (${error::class.java.name})")
            if (error is CliError) error.exit else EXIT_FAILURE
        }
    exitProcess(exit)
}

/**
 * The invite comes from stdin only, never argv or the environment (#79 plan SEC-6): argv shows up in process lists, shell
 * history and CI logs. Always a plain line from stdin, echoed if typed: `System.console()` can't tell a terminal from a
 * pipe on JDK 22+, and on 21 it is null when stdout is redirected, so a no-echo read would depend on the JDK and on
 * redirects (#103 CR-3, SEC-14). The prompt goes to stderr, never into piped output.
 */
private fun readInvite(): String {
    System.err.print("invite code or link: ")
    System.err.flush()
    return readlnOrNull() ?: throw CliError("no invite on stdin", EXIT_USAGE)
}

/** The text of a chat message: one line from stdin (never argv: it would show up in process lists and shell history). */
private fun readText(): String {
    System.err.print("message: ")
    System.err.flush()
    return readlnOrNull() ?: throw CliError("no text on stdin", EXIT_USAGE)
}
