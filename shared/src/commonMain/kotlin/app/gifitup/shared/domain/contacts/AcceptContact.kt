package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.contacts.AcceptResult
import app.gifitup.shared.data.contacts.Observation

/** Opt-in for the dev CLI's blind accept; an error to use it anywhere else without saying so (ADR-0014). */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Accepts a contact without showing anything to compare: dev CLI and test accounts only (ADR-0014).",
)
@Retention(AnnotationRetention.BINARY)
annotation class BlindAcceptForDevCli

/**
 * An observation that a screen showed to the user, with the account it belongs to. Only the Friends screen's holder can
 * make one ([ContactsStateHolder.review][app.gifitup.shared.presentation.contacts.ContactsStateHolder]; the constructor
 * is internal to `:shared`), so no other code can trust a contact without having shown its emojis first (ADR-0014).
 */
class ReviewedObservation internal constructor(
    internal val accountId: String,
    internal val observation: Observation,
)

/**
 * Trusts a blocked contact's new observation, exactly the one that was shown (ADR-0014): [reviewed] takes a
 * [ReviewedObservation] and is internal to `:shared`; [blindly] is for the dev CLI and test accounts and needs an
 * explicit opt-in, so an app screen can't call either by accident (a test also keeps the names out of the app modules).
 */
class AcceptContact(
    private val access: ContactBookAccess,
) {
    internal suspend fun reviewed(shown: ReviewedObservation): AcceptOutcome = accept(shown.accountId, shown.observation)

    @BlindAcceptForDevCli
    suspend fun blindly(
        accountId: String,
        observation: Observation,
    ): AcceptOutcome = accept(accountId, observation)

    private suspend fun accept(
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
