package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.TextRules

/**
 * Counts user-perceived characters (extended grapheme clusters) for the caption counter. The platform supplies it
 * (`BreakIterator` on Android, `Intl.Segmenter` in the browser): the count only drives the 80-character limit of the
 * input, while the hard limit that every receiver checks is the codec's 120 code points (ADR-0006 B3a).
 */
fun interface GraphemeCounter {
    fun count(text: String): Int
}

enum class CaptionVerdict {
    /** Fine to send ([CaptionCheck.caption] is `null` for none). */
    OK,

    /** More than [CaptionInput.MAX_GRAPHEMES] characters, or more code points than the codec takes. */
    TOO_LONG,

    /** Breaks the text rules (a control character, a lone surrogate, nothing visible, ...). */
    INVALID,
}

/** [graphemes] is the live counter's value: the trimmed text when it is valid, else the text as typed. */
class CaptionCheck(
    val verdict: CaptionVerdict,
    val graphemes: Int,
    val caption: String?,
)

/** The caption field's rules (#80): optional, one line, 80 characters, plus the codec's rules, sent trimmed. */
class CaptionInput(
    private val counter: GraphemeCounter,
) {
    fun check(raw: String): CaptionCheck {
        // The text rules without the length cap first, so "too long" and "invalid" are told apart.
        val trimmed = TextRules.clean(raw, Int.MAX_VALUE) ?: return CaptionCheck(CaptionVerdict.INVALID, counter.count(raw), null)
        val graphemes = counter.count(trimmed)
        val fits = graphemes <= MAX_GRAPHEMES && TextRules.clean(trimmed, ContentCodec.CAPTION_MAX_CODE_POINTS) != null
        return when {
            !fits -> CaptionCheck(CaptionVerdict.TOO_LONG, graphemes, null)
            else -> CaptionCheck(CaptionVerdict.OK, graphemes, trimmed.ifEmpty { null })
        }
    }

    companion object {
        const val MAX_GRAPHEMES = 80
    }
}
