package app.gifitup.ui

import app.gifitup.shared.domain.media.UnavailableReason
import app.gifitup.shared.domain.messages.CaptionVerdict
import app.gifitup.shared.domain.messages.MembershipEvent
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.presentation.chat.SendProblem

/** The fixed words of the conversation screen (#25); a friend's text is isolated like in [ChatTexts]. */
@Suppress("TooManyFunctions") // One small text function per message of the conversation screen.
internal object ConversationTexts {
    const val SEND_GIF = "Send"
    const val OPEN_GROUP = "Group info"
    const val SEND_TITLE = "Send this GIF"
    const val CAPTION_LABEL = "Add a caption (optional)"
    const val SEND = "Choose a GIF to send"
    const val RESEND = "Send again"
    const val YOU = "you"
    const val SENT = "sent"
    const val GIF_CHIP = "GIF"
    const val ENCRYPTED = "end-to-end encrypted"
    const val SENDING = "Sending…"
    const val NOT_SENT = "Not sent"
    const val REACT = "React"
    const val REACTIONS_TITLE = "Reactions"
    const val NO_REACTIONS = "No reactions yet."
    const val YOUR_REACTION = "Your reaction"
    const val REMOVE_REACTION = "Remove my reaction"
    const val CLOSE = "Close"
    const val NO_EMOJI_HERE = "This device can't draw any of the reaction emojis yet."

    /** A chip under a GIF, for a screen reader: the emoji, how many, and whether the user is one of them. */
    fun reactionChip(
        emoji: String,
        count: Int,
        mine: Boolean,
    ): String = "$emoji, $count" + if (mine) ", including you" else ""

    /** One line of the list of who reacted; the name is isolated like every name from a friend. */
    fun reactedBy(
        name: String,
        emoji: String,
    ): String = "${ChatTexts.isolated(name)}  $emoji"

    /** A message that was stored but never confirmed, in a chat that can't be used: it is not sent again from here. */
    const val NOT_CONFIRMED = "A message was not confirmed by the server. It can't be sent again while this chat can't be used."

    fun unreadable(count: Int): String =
        if (count ==
            1
        ) {
            "1 stored message could not be read."
        } else {
            "$count stored messages could not be read."
        }

    const val PAUSE_GIF = "Pause the GIF"
    const val PLAY_GIF = "Play the GIF"
    const val PAUSED = "Paused"
    const val RETRY_GIF = "Try loading the GIF again"
    const val PICK_CHAT = "Choose a chat to read it."
    const val LOADING_GIF = "Loading GIF…"
    const val GIF_FAILED_RETRY = "Tap to try again"
    const val GIF_CANT_SHOW = "This GIF can't be shown"
    const val UNAVAILABLE = "Message unavailable"
    const val UPDATE_NEEDED = "Update the app to see this message"

    fun gifUnavailable(reason: UnavailableReason): String =
        when (reason) {
            UnavailableReason.FETCH_FAILED -> "The GIF could not be loaded."
            UnavailableReason.TOO_LARGE -> "The GIF is too large to show."
            UnavailableReason.REJECTED -> "The GIF is not a file this app shows."
        }

    /** The line under the title: how many people are in it (you included), and that it is encrypted. */
    fun subtitle(
        others: List<String>,
        named: Boolean,
    ): String {
        val encrypted = ENCRYPTED.replaceFirstChar { it.uppercase() }
        return when {
            // A name is text another member chose: the real usernames stay in view next to it (#217).
            named && others.size in 1..MAX_NAMED_MEMBERS -> "${others.joinToString(", ") { ChatTexts.isolated(it) }} · $ENCRYPTED"

            others.size < 2 -> encrypted

            else -> "${others.size + 1} friends · $ENCRYPTED"
        }
    }

    private const val MAX_NAMED_MEMBERS = 4

    /**
     * A roster change this device processed. The names are the MLS credentials' (isolated like any name). "Removed by" says
     * only who sent the commit: whether the person left or was removed is not provable here (#31).
     */
    fun membership(notice: MessageBody.Membership): String =
        when (notice.event) {
            MembershipEvent.ADDED -> {
                "${whoUpper(notice.by)} added ${ChatTexts.isolated(notice.subject)}"
            }

            MembershipEvent.REMOVED -> {
                "${ChatTexts.isolated(notice.subject)} was removed by ${who(notice.by)}"
            }

            MembershipEvent.CHANGED -> {
                "The members of this chat changed"
            }

            MembershipEvent.JOINED -> {
                "You joined this chat with ${others(notice.count - 1)}" +
                    (notice.by?.let { ", added by ${ChatTexts.isolated(it)}" } ?: "")
            }
        }

    private fun others(count: Int): String = if (count == 1) "1 other" else "$count others"

    private fun who(name: String?): String = name?.let(ChatTexts::isolated) ?: YOU

    private fun whoUpper(name: String?): String = name?.let(ChatTexts::isolated) ?: YOU.replaceFirstChar { it.uppercase() }

    fun renamed(name: String): String = "The chat was renamed to ${ChatTexts.isolated(name)}"

    fun counter(
        graphemes: Int,
        limit: Int,
    ): String = "$graphemes/$limit"

    fun captionProblem(verdict: CaptionVerdict): String? =
        when (verdict) {
            CaptionVerdict.OK -> null
            CaptionVerdict.TOO_LONG -> "The caption is too long."
            CaptionVerdict.INVALID -> "The caption has characters that can't be sent."
        }

    fun sendProblem(problem: SendProblem): String =
        when (problem) {
            SendProblem.CAPTION -> "The caption can't be sent. Nothing was sent."
            SendProblem.EARLIER_FIRST -> "An earlier message is still waiting. Send it again first."
            SendProblem.IN_PROGRESS -> "The last message is still being sent."
            SendProblem.SYNC_FIRST -> "New messages are waiting. They are read first; then send again."
            SendProblem.STOPPED -> "This chat can't be used right now."
            SendProblem.OFFLINE -> "No answer from the server. Nothing was sent."
            SendProblem.THROTTLED -> ChatTexts.THROTTLED_SEND
            SendProblem.REFUSED -> "The server refused the message."
            SendProblem.HISTORY -> "The history on this device isn't available right now. Nothing was sent."
            SendProblem.FAILED -> "That did not work."
        }

    fun gifDescription(
        sender: String,
        caption: String?,
    ): String = "GIF from $sender" + (caption?.let { ", caption: ${ChatTexts.isolated(it)}" } ?: "")
}
