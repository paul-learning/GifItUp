package app.gifitup.shared.presentation.contacts

import app.gifitup.shared.data.contacts.AcceptResult
import app.gifitup.shared.data.contacts.Contact
import app.gifitup.shared.data.contacts.ContactBook
import app.gifitup.shared.data.contacts.ContactStatus
import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.contacts.TrustConflict
import app.gifitup.shared.data.crypto.FingerprintEmoji
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.storage.ContactLoad
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.AcceptOutcome
import app.gifitup.shared.domain.contacts.ContactsUseCases
import app.gifitup.shared.domain.contacts.Fingerprints
import app.gifitup.shared.domain.contacts.ReviewedObservation
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the app has saved for a friend, as the screen words it. [NOT_SAVED]: only the server's member list has shown
 * this account, so nothing is pinned and no emojis are shown (ADR-0014, SEC-42).
 */
enum class FriendStatus { SAVED, NOT_SAVED, CHANGED, GONE }

enum class FriendsProblem {
    /** The contact book is missing, damaged or can't be written (ADR-0014): nothing is shown from it. */
    BOOK_UNAVAILABLE,

    /** The emojis couldn't be computed: no friend shows any and nothing can be trusted. */
    EMOJIS_FAILED,

    OFFLINE,
    NOT_SIGNED_IN,

    /** The book refused the trust (the observation can't be pinned). */
    TRUST_REFUSED,

    FAILED,
}

/**
 * One friend. [ref] is opaque: the screen hands it back to [ContactsStateHolder.review] and never shows it. [devices]
 * are the emojis of the keys the app has saved (one list per device); empty for [FriendStatus.NOT_SAVED].
 */
class FriendView(
    val ref: String,
    val username: String,
    val status: FriendStatus,
    val reason: TrustConflict?,
    val devices: List<List<FingerprintEmoji>>,
)

/**
 * The "trust the new key" review of one blocked friend, with everything on it captured when it was opened: the screen
 * renders only this, never a recomputed state, and [trust][ContactsStateHolder.trust] accepts exactly [shown]
 * (ADR-0014, SEC-43). [version] changes whenever the review is rebuilt, so a confirmation tick must not survive it.
 * [changes] counts how often the server changed the observation while the review was open.
 */
@Suppress("LongParameterList") // One review as the screen renders it; grouping the fields would only rename them.
class ReviewView internal constructor(
    val ref: String,
    val version: Int,
    val reason: TrustConflict,
    val username: String,
    val observedUsername: String,
    val pinned: List<List<FingerprintEmoji>>,
    val observed: List<List<FingerprintEmoji>>,
    val resulting: List<List<FingerprintEmoji>>,
    val changes: Int,
    val canTrust: Boolean,
    internal val shown: ReviewedObservation,
)

data class FriendsState(
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val own: List<FingerprintEmoji>? = null,
    val friends: List<FriendView> = emptyList(),
    val review: ReviewView? = null,
    val problem: FriendsProblem? = null,
)

/**
 * The Friends screen's logic (#18, ADR-0014). The screen shows what the contact book has saved, refreshed from the
 * server's member list, and is the one place where a blocked friend is trusted again:
 * - Emojis come only from saved keys, never from the live list; a friend only the list has shown is "not saved" and has
 *   none (SEC-42). The user's own emojis come from the local identity.
 * - [review] captures the pending observation and the emojis of the pinned and observed keys; [trust] accepts exactly
 *   that observation and only after an explicit confirmation. If the server changed it meanwhile the review is rebuilt
 *   from the new one and nothing is pinned; from the second change on, trust is off for that review (SEC-43).
 * - A refresh is ignored while a review is open, so the displayed set never changes under the user.
 * - One action at a time; an answer for an older screen visit is dropped. Nothing is saved: [leave] drops it all.
 */
@Suppress("TooManyFunctions") // One screen's actions and their private steps.
class ContactsStateHolder(
    private val scope: CoroutineScope,
    private val sessions: SessionHolder,
    private val contacts: ContactsUseCases,
) {
    private val flow = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = flow.asStateFlow()

    /** Bumped by [leave]: an answer for an older visit is dropped. */
    private var visit = 0

    /** Shows the saved list at once, then refreshes it from the server. */
    fun open() =
        act { forVisit ->
            show(forVisit, contacts.access.read())
            refreshAndShow(forVisit)
        }

    fun refresh() = act { forVisit -> if (flow.value.review == null) refreshAndShow(forVisit) }

    /** Opens the review of a blocked friend. */
    fun review(ref: String) = act { forVisit -> openReview(forVisit, ref, previous = null) }

    fun cancelReview() = flow.update { it.copy(review = null) }

    /** Trusts the shown observation; [confirmed] is the user's explicit "I compared all 12 emojis and names". */
    fun trust(confirmed: Boolean) =
        act { forVisit ->
            val review = flow.value.review
            if (review != null && confirmed && review.canTrust) accept(forVisit, review)
        }

    /** Drops everything, own emojis and an open review included. */
    fun leave() {
        visit++
        flow.value = FriendsState()
    }

    private suspend fun accept(
        forVisit: Int,
        review: ReviewView,
    ) {
        when (val outcome = contacts.accept.reviewed(review.shown)) {
            is AcceptOutcome.Result -> {
                when (outcome.result) {
                    AcceptResult.Accepted -> closeReviewAndShow(forVisit)
                    AcceptResult.Changed -> openReview(forVisit, review.ref, previous = review)
                    AcceptResult.Refused -> closeReviewAndShow(forVisit, FriendsProblem.TRUST_REFUSED)
                }
            }

            AcceptOutcome.Unavailable -> {
                change(forVisit) { it.copy(review = null, problem = FriendsProblem.BOOK_UNAVAILABLE) }
            }
        }
    }

    private suspend fun closeReviewAndShow(
        forVisit: Int,
        problem: FriendsProblem? = null,
    ) {
        change(forVisit) { it.copy(review = null) }
        show(forVisit, contacts.access.read())
        if (problem != null) change(forVisit) { it.copy(problem = problem) }
    }

    private suspend fun refreshAndShow(forVisit: Int) {
        when (val refreshed = sessions.withSessionResult { contacts.refresh(it) }) {
            is AuthResult.Ok -> show(forVisit, contacts.access.read())
            is AuthResult.Failed -> change(forVisit) { it.copy(problem = it.problem ?: refreshed.error.toProblem()) }
        }
    }

    private suspend fun show(
        forVisit: Int,
        loaded: ContactLoad,
    ) {
        when (loaded) {
            ContactLoad.Unavailable -> {
                change(forVisit) { it.copy(loaded = true, friends = emptyList(), own = null, problem = FriendsProblem.BOOK_UNAVAILABLE) }
            }

            is ContactLoad.Book -> {
                val saved =
                    loaded.book.contacts.values
                        .filter { it.status != ContactStatus.UNCONFIRMED }
                when (val fingerprints = contacts.fingerprints(saved.flatMap { it.devices.keys }.toSet())) {
                    is AuthResult.Ok -> {
                        val friends =
                            loaded.book.contacts.values
                                .map { it.toView(fingerprints.value) }
                        change(forVisit) { it.copy(loaded = true, own = fingerprints.value.own, friends = order(friends)) }
                    }

                    is AuthResult.Failed -> {
                        val friends =
                            loaded.book.contacts.values
                                .map { it.toView(null) }
                        change(forVisit) {
                            it.copy(loaded = true, own = null, friends = order(friends), problem = FriendsProblem.EMOJIS_FAILED)
                        }
                    }
                }
            }
        }
    }

    private suspend fun openReview(
        forVisit: Int,
        ref: String,
        previous: ReviewView?,
    ) {
        val book = (contacts.access.read() as? ContactLoad.Book)?.book
        val contact = book?.contacts?.get(ref)?.takeIf { it.status == ContactStatus.BLOCKED && it.pending != null }
        if (book == null || contact == null) {
            change(forVisit) { it.copy(review = null) }
            show(forVisit, contacts.access.read())
            return
        }
        val observation = checkNotNull(contact.pending)
        val keys = contact.devices.keys + observation.devices.keys
        when (val fingerprints = contacts.fingerprints(keys)) {
            is AuthResult.Failed -> {
                change(forVisit) { it.copy(review = null, problem = FriendsProblem.EMOJIS_FAILED) }
            }

            is AuthResult.Ok -> {
                val emojis = fingerprints.value
                val resulting = book.resultOfAccepting(ref, observation)
                val changes = (previous?.changes ?: 0) + if (previous != null) 1 else 0
                val review =
                    ReviewView(
                        ref = ref,
                        version = (previous?.version ?: 0) + 1,
                        reason = observation.reason,
                        username = contact.username,
                        observedUsername = observation.username,
                        pinned = cells(contact.devices, emojis),
                        observed = cells(observation.devices, emojis),
                        resulting = resulting?.let { cells(it, emojis) }.orEmpty(),
                        changes = changes,
                        canTrust = resulting != null && changes < MAX_CHANGES,
                        shown = ReviewedObservation(ref, observation),
                    )
                change(forVisit) { it.copy(review = review) }
            }
        }
    }

    private fun cells(
        devices: Map<KeyBytes, String>,
        fingerprints: Fingerprints,
    ): List<List<FingerprintEmoji>> = devices.entries.sortedBy { it.value }.mapNotNull { fingerprints.of(it.key) }

    private fun Contact.toView(fingerprints: Fingerprints?): FriendView =
        FriendView(
            ref = accountId,
            username = username,
            status =
                when (status) {
                    ContactStatus.OK -> FriendStatus.SAVED
                    ContactStatus.UNCONFIRMED -> FriendStatus.NOT_SAVED
                    ContactStatus.BLOCKED -> FriendStatus.CHANGED
                    ContactStatus.GONE -> FriendStatus.GONE
                },
            reason = pending?.reason,
            devices = if (status == ContactStatus.UNCONFIRMED || fingerprints == null) emptyList() else cells(devices, fingerprints),
        )

    private fun order(friends: List<FriendView>): List<FriendView> =
        friends.sortedWith(compareBy({ it.status != FriendStatus.CHANGED }, { it.username }))

    private inline fun change(
        forVisit: Int,
        update: (FriendsState) -> FriendsState,
    ) {
        if (forVisit == visit) flow.update { update(it) }
    }

    /** Runs [block] unless another action is running; the busy flag is set before this returns. */
    private fun act(block: suspend (forVisit: Int) -> Unit) {
        val forVisit = visit
        if (flow.getAndUpdate { if (it.busy) it else it.copy(busy = true, problem = null) }.busy) return
        scope.launch {
            try {
                block(forVisit)
            } finally {
                change(forVisit) { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        /** From the second change on, the server keeps rotating the observation: trust stays off for that review. */
        const val MAX_CHANGES = 2
    }
}

private fun AuthError.toProblem(): FriendsProblem =
    when (this) {
        is AuthError.Api -> {
            when (error) {
                ApiError.NETWORK -> FriendsProblem.OFFLINE
                ApiError.UNAUTHORIZED -> FriendsProblem.NOT_SIGNED_IN
                else -> FriendsProblem.FAILED
            }
        }

        is AuthError.Local -> {
            when (this) {
                AuthError.Local.NOT_REGISTERED -> FriendsProblem.NOT_SIGNED_IN
                AuthError.Local.CONTACTS_UNAVAILABLE -> FriendsProblem.BOOK_UNAVAILABLE
                else -> FriendsProblem.FAILED
            }
        }
    }
