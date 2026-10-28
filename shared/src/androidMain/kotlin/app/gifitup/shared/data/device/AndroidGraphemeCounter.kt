package app.gifitup.shared.data.device

import android.icu.text.BreakIterator
import app.gifitup.shared.domain.messages.GraphemeCounter

/** Extended grapheme clusters by ICU (API 24+, so always here: the minimum is 28). */
class AndroidGraphemeCounter : GraphemeCounter {
    override fun count(text: String): Int {
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var count = 0
        while (breaks.next() != BreakIterator.DONE) count++
        return count
    }
}
