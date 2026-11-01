package app.gifitup.ui

import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.gif.GifPickerStateHolder

/** The picker's two holders: what is shown (results, search) and how the previews load (like the bubbles of a chat). */
internal class PickerParts(
    val picker: GifPickerStateHolder,
    val previews: GifTilesStateHolder,
)
