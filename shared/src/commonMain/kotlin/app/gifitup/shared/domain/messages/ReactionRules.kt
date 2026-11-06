package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.ReactionEmojis

/**
 * What a store may do with a reaction (ADR-0006 B10a). One place, so every store applies the same rules:
 * - only a GIF takes reactions (not a rename, a notice or an unavailable message);
 * - the reactor is a username that passes the username rule, from the MLS credential;
 * - the emoji is exactly an entry of [ReactionEmojis], or `null` to take the reaction back;
 * - one reaction per member, the latest wins, and at most [MAX_PER_MESSAGE] members per message: a further member is dropped,
 *   one who reacted already can still change or remove theirs. The cap keeps the record far under the cipher's size limit.
 */
internal object ReactionRules {
    const val MAX_PER_MESSAGE = 32
    private val USERNAME = Regex("^[a-z0-9_]{3,32}$")

    fun validUsername(name: String): Boolean = USERNAME.matches(name)

    /** The reactions of [message] after [by] reacted with [emoji], or `null` if nothing changes or the reaction is not allowed. */
    fun apply(
        message: ChatMessage,
        by: String,
        emoji: String?,
    ): List<Reaction>? {
        val entry = emoji?.let { ReactionEmojis.canonical(it) }
        val allowed = message.body is MessageBody.Gif && validUsername(by) && (emoji == null || entry != null)
        return if (allowed) change(message.reactions, by, entry) else null
    }

    private fun change(
        current: List<Reaction>,
        by: String,
        entry: String?,
    ): List<Reaction>? {
        val at = current.indexOfFirst { it.by == by }
        return when {
            entry == null -> if (at < 0) null else current.filterIndexed { index, _ -> index != at }
            at >= 0 -> if (current[at].emoji == entry) null else current.toMutableList().also { it[at] = Reaction(by, entry) }
            current.size >= MAX_PER_MESSAGE -> null
            else -> current + Reaction(by, entry)
        }
    }
}
