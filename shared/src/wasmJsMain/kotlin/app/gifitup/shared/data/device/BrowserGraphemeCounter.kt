package app.gifitup.shared.data.device

import app.gifitup.shared.domain.messages.GraphemeCounter

/** Extended grapheme clusters by the browser's `Intl.Segmenter`; code points where a browser lacks it (an older Firefox). */
class BrowserGraphemeCounter : GraphemeCounter {
    override fun count(text: String): Int = jsGraphemes(text)
}

@JsFun(
    """
(text) => {
  if (typeof Intl === 'undefined' || typeof Intl.Segmenter !== 'function') return Array.from(text).length;
  let count = 0;
  for (const _ of new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)) count++;
  return count;
}
""",
)
private external fun jsGraphemes(text: String): Int
