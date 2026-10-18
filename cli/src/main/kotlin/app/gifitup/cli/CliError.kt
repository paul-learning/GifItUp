package app.gifitup.cli

/** An error with a fixed message that is safe to print, and the exit code it ends the process with. */
class CliError(
    override val message: String,
    val exit: Int,
) : Exception(message)

const val EXIT_OK = 0

/** The server refused, answered outside the protocol, or didn't answer. */
const val EXIT_SERVER = 1
const val EXIT_USAGE = 2

/** Refused on this machine: the account guard, the state dir, or a local check. */
const val EXIT_REFUSED = 3

/** Anything unexpected; only its class name is printed. */
const val EXIT_FAILURE = 4
