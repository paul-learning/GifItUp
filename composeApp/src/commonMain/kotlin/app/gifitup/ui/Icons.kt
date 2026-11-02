@file:Suppress("MagicNumber") // The proportions of a drawn icon are its content.

package app.gifitup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// More drawn icons of the design (the first ones are in Components.kt): no icon font, no extra dependency.

/** An exclamation mark in a ring: something to look at (the custom-server marker). */
@Composable
internal fun WarningIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val width = 2.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2 - width / 2, style = Stroke(width))
        drawLine(color, Offset(size.width / 2, size.height * 0.26f), Offset(size.width / 2, size.height * 0.56f), width, StrokeCap.Round)
        drawCircle(color, radius = 1.4.dp.toPx(), center = Offset(size.width / 2, size.height * 0.72f))
    }
}
