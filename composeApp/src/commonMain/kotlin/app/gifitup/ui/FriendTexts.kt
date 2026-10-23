package app.gifitup.ui

import app.gifitup.shared.data.contacts.TrustConflict
import app.gifitup.shared.presentation.contacts.FriendStatus
import app.gifitup.shared.presentation.contacts.FriendsProblem

/**
 * The Friends screen's fixed texts (ADR-0014). Words the app may not use: "verified", "safe", "secure". What the app
 * has is a key it saved; what the user did is compare, outside the app.
 */
internal object FriendTexts {
    const val OWN_TITLE = "Your emojis"
    const val OWN_HINT =
        "Read these emojis to your friend and ask them to read theirs. Do this on a call or in person, not in a chat."

    fun allTwelve(glyphsAllKnown: Boolean): String =
        if (glyphsAllKnown) {
            "All 12 emojis and names must match what your friend sees on their own phone."
        } else {
            "All 12 names must match what your friend sees on their own phone."
        }

    const val GLYPH_NOTICE =
        "This device may not draw some of these emojis, and they would all look like the same box. Compare the names, not the pictures."

    fun status(status: FriendStatus): String =
        when (status) {
            FriendStatus.SAVED -> "Saved. These are the emojis the app has saved for them. To check, ask your friend to read theirs."
            FriendStatus.NOT_SAVED -> "Not saved yet. The server lists them, but this device has not saved a key for them."
            FriendStatus.CHANGED -> "Something changed. Review it before you trust this friend."
            FriendStatus.GONE -> "Not in your list any more. The saved key is kept."
        }

    fun problem(problem: FriendsProblem): String =
        when (problem) {
            FriendsProblem.BOOK_UNAVAILABLE -> "This device's friends list can't be used."
            FriendsProblem.EMOJIS_FAILED -> "The emojis couldn't be worked out, so nothing can be trusted right now."
            FriendsProblem.OFFLINE -> "No answer from the server. The list below is what this device has saved."
            FriendsProblem.NOT_SIGNED_IN -> "The server didn't accept this device."
            FriendsProblem.TRUST_REFUSED -> "That can't be trusted: its key belongs to another friend, or its name is taken."
            FriendsProblem.FAILED -> "Something went wrong. Try again."
        }

    fun reviewTitle(reason: TrustConflict): String =
        when (reason) {
            TrustConflict.KEY_CHANGED -> "The key changed"
            TrustConflict.DEVICE_ADDED -> "A new phone was added"
            TrustConflict.MULTIPLE_DEVICES -> "This friend shows several phones"
            TrustConflict.RENAMED -> "The name changed"
            TrustConflict.USERNAME_REASSIGNED -> "This name now belongs to a different account"
            TrustConflict.KEY_CONFLICT -> "This key is saved for another friend"
            TrustConflict.BOOK_FULL -> "Your friends list is full"
        }

    fun reviewBody(reason: TrustConflict): String =
        when (reason) {
            TrustConflict.KEY_CHANGED -> {
                "Your friend may have a new phone, or someone is pretending. Only a comparison outside the app can tell."
            }

            TrustConflict.DEVICE_ADDED, TrustConflict.MULTIPLE_DEVICES -> {
                "Every one of these must be a phone your friend holds and reads out. If your friend has only one phone, do not trust."
            }

            TrustConflict.RENAMED -> {
                "The same account now has another name. Matching emojis show the key is the same, not the name."
            }

            TrustConflict.USERNAME_REASSIGNED -> {
                "The old account with this name is gone and another one took it. " +
                    "Trust it only if your friend says this is their new account."
            }

            TrustConflict.KEY_CONFLICT -> {
                "It can't be trusted for this friend. Nothing you can do here changes that."
            }

            TrustConflict.BOOK_FULL -> {
                "This friend can't be added."
            }
        }

    /** The trust button says what changes; [KEY_CONFLICT][TrustConflict.KEY_CONFLICT] has none. */
    fun trustButton(reason: TrustConflict): String? =
        when (reason) {
            TrustConflict.KEY_CHANGED -> "Trust the new key"
            TrustConflict.DEVICE_ADDED -> "Trust the new phone"
            TrustConflict.MULTIPLE_DEVICES -> "Trust these phones"
            TrustConflict.RENAMED -> "Trust the new name"
            TrustConflict.USERNAME_REASSIGNED -> "Trust this account"
            TrustConflict.KEY_CONFLICT, TrustConflict.BOOK_FULL -> null
        }

    fun confirmation(glyphsAllKnown: Boolean): String =
        if (glyphsAllKnown) {
            "I compared all 12 emojis and names with my friend, on a call or in person, not in a chat."
        } else {
            "I compared all 12 names with my friend, on a call or in person, not in a chat."
        }

    const val CHANGED_NOTE = "This changed while you were looking. The emojis below are new. Compare them again."
    const val KEEPS_CHANGING = "The key keeps changing, which is unusual. Don't trust it now."
}
