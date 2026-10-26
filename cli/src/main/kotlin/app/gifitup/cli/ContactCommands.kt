package app.gifitup.cli

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.AcceptContact
import app.gifitup.shared.domain.contacts.AcceptOutcome
import app.gifitup.shared.domain.contacts.AcceptResult
import app.gifitup.shared.domain.contacts.BlindAcceptForDevCli
import app.gifitup.shared.domain.contacts.Contact
import app.gifitup.shared.domain.contacts.ContactBook
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.RefreshContacts
import java.io.PrintStream

/** The contact commands (ADR-0014, #18), thin over the app's use cases. Fixed output lines, public data only. */
@OptIn(BlindAcceptForDevCli::class) // The dev CLI is the one caller of the blind accept (test accounts, ADR-0014).
internal class ContactCommands(
    private val refresh: RefreshContacts,
    private val acceptContact: AcceptContact,
    private val access: ContactBookAccess,
    private val out: PrintStream,
    private val report: suspend (AuthResult.Failed) -> Int,
) {
    /** Refreshes the book from the member list and prints it: one line per contact, usernames and ids only, no key. */
    suspend fun list(session: Session): Int =
        when (val result = refresh(session)) {
            is AuthResult.Ok -> {
                describe(result.value).forEach(out::println)
                EXIT_OK
            }

            is AuthResult.Failed -> {
                report(result)
            }
        }

    /**
     * Accepts the pending observation of a blocked contact **without showing or checking anything**: a dev tool for
     * test accounts (the guard already ran). The app has no such action until it can show a fingerprint (ADR-0014).
     */
    suspend fun accept(accountId: String): Int {
        val contact = blocked(accountId)
        return when (val outcome = acceptContact.blindly(accountId, checkNotNull(contact.pending))) {
            is AcceptOutcome.Result -> acceptedLine(contact, outcome.result)
            AcceptOutcome.Unavailable -> throw CliError("the contact book couldn't be updated", EXIT_REFUSED)
        }
    }

    private suspend fun blocked(accountId: String): Contact {
        val loaded = access.read() as? ContactLoad.Book ?: throw CliError("the contact book is missing or damaged", EXIT_REFUSED)
        return loaded.book.contacts[accountId]?.takeIf { it.status == ContactStatus.BLOCKED && it.pending != null }
            ?: throw CliError("no blocked contact with that account id", EXIT_REFUSED)
    }

    private fun acceptedLine(
        contact: Contact,
        result: AcceptResult,
    ): Int =
        when (result) {
            AcceptResult.Accepted -> {
                out.println("accepted ${contact.username} blindly (test accounts only)")
                EXIT_OK
            }

            AcceptResult.Changed -> {
                throw CliError("the observation changed meanwhile; run contacts and try again", EXIT_REFUSED)
            }

            AcceptResult.Refused -> {
                throw CliError("this observation can't be accepted", EXIT_REFUSED)
            }
        }

    private fun describe(book: ContactBook): List<String> =
        book.contacts.values.sortedWith(compareBy({ it.username }, { it.accountId })).map { c ->
            val reason =
                c.pending
                    ?.reason
                    ?.let { "  ${it.name.lowercase()}" }
                    .orEmpty()
            "${c.accountId}  ${c.username}  ${c.status.name.lowercase()}  devices ${c.devices.size}$reason"
        }
}
