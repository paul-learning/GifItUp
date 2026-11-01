package app.gifitup.ui

import app.gifitup.shared.domain.media.UnavailableReason
import app.gifitup.shared.domain.messages.CaptionVerdict
import app.gifitup.shared.presentation.chat.SendProblem

/** The fixed words of the conversation screen (#25); a friend's text is isolated like in [ChatTexts]. */
internal object ConversationTexts {
    const val ADD_CAPTION = "+ add caption"
    const val REMOVE_CAPTION = "Remove caption"
    const val CAPTION_LABEL = "Caption (optional)"
    const val SEND = "Choose a GIF to send"
    const val RESEND = "Send again"
    const val YOU = "you"
    const val SENT = "sent"
    const val GIF_CHIP = "GIF"
    const val ENCRYPTED = "end-to-end encrypted"
    const val SENDING = "Sending…"
    const val NOT_SENT = "Not sent"

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

    const val LOAD_GIF = "Load the GIF"
    const val RETRY_GIF = "Try loading the GIF again"
    const val PICK_CHAT = "Choose a chat to read it."
    const val TAP_TO_LOAD = "Tap to load this GIF"
    const val TAP_TO_LOAD_WHY = "This phone's security updates are old or can't be read, so GIFs aren't loaded on their own."
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
    fun subtitle(others: Int): String {
        val encrypted = ENCRYPTED.replaceFirstChar { it.uppercase() }
        return if (others < 2) encrypted else "${others + 1} friends · $ENCRYPTED"
    }

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
            SendProblem.REFUSED -> "The server refused the message."
            SendProblem.HISTORY -> "The history on this device isn't available right now. Nothing was sent."
            SendProblem.FAILED -> "That did not work."
        }

    fun gifDescription(
        sender: String,
        caption: String?,
    ): String = "GIF from $sender" + (caption?.let { ", caption: ${ChatTexts.isolated(it)}" } ?: "")
}
