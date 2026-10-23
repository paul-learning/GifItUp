package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.contacts.AcceptResult
import app.gifitup.shared.data.contacts.Observation

/**
 * Trusts a blocked contact's new observation, exactly the one the caller showed (ADR-0014). **Not wired into any app
 * screen** until the fingerprint exists (#18 PR 3/4): without something the user can compare, this would be a blind
 * click. Only the dev CLI (test accounts, which says so) uses it.
 */
class AcceptContact(
    private val access: ContactBookAccess,
) {
    suspend operator fun invoke(
        accountId: String,
        shown: Observation,
    ): AcceptOutcome =
        when (val updated = access.update { book, _ -> book.accept(accountId, shown) }) {
            is BookUpdate.Done -> AcceptOutcome.Result(updated.value)
            BookUpdate.Unavailable, BookUpdate.NotSaved -> AcceptOutcome.Unavailable
        }
}

sealed interface AcceptOutcome {
    class Result(
        val result: AcceptResult,
    ) : AcceptOutcome

    data object Unavailable : AcceptOutcome
}
