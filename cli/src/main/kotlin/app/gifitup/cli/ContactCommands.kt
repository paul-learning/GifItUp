package app.gifitup.cli

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.ListMembers
import java.io.PrintStream

/** The contact commands (ADR-0014, #18), thin over the app's use cases. Fixed output lines, public data only. */
internal class ContactCommands(
    private val members: ListMembers,
    private val out: PrintStream,
    private val report: suspend (AuthResult.Failed) -> Int,
) {
    /** One line per neighbour, then `more` if the server cut the list. Usernames only; no key, no token. */
    suspend fun list(session: Session): Int =
        when (val result = members(session)) {
            is AuthResult.Ok -> {
                result.value.members.forEach {
                    out.println("${it.accountId}  ${it.username}  ${it.relationship.name.lowercase()}  devices ${it.devices.size}")
                }
                if (result.value.more) out.println("more members exist than this list shows")
                EXIT_OK
            }

            is AuthResult.Failed -> {
                report(result)
            }
        }
}
