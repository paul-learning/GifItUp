@file:Suppress("MagicNumber") // Coordinates of the drawn icons are fractions of their box.

package app.gifitup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The shared pieces of the visual design (#154): top bar, cards, avatars, small icon buttons. Icons are drawn, not fonts or files.

/** A screen's top bar: the rounded back button, the title (and a line under it), and room for something on the right. */
@Composable
internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onBack != null) {
            IconTile(ChatTexts.BACK, onBack) { color -> Chevron(color) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

/** A rounded square button of at least the 48 dp touch size, with a drawn icon; [label] is what a screen reader says. */
@Composable
internal fun IconTile(
    label: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(TILE_SIZE)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon(color) }
}

@Composable
internal fun Chevron(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(
            color,
            Offset(size.width * 0.65f, size.height * 0.15f),
            Offset(size.width * 0.3f, size.height * 0.5f),
            stroke.width,
            stroke.cap,
        )
        drawLine(
            color,
            Offset(size.width * 0.3f, size.height * 0.5f),
            Offset(size.width * 0.65f, size.height * 0.85f),
            stroke.width,
            stroke.cap,
        )
    }
}

/** An "i" in a ring. */
@Composable
internal fun InfoIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val width = 2.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2 - width / 2, style = Stroke(width))
        drawCircle(color, radius = 1.4.dp.toPx(), center = Offset(size.width / 2, size.height * 0.32f))
        drawLine(color, Offset(size.width / 2, size.height * 0.46f), Offset(size.width / 2, size.height * 0.74f), width, StrokeCap.Round)
    }
}

/** A card of the design: a container surface with rounded corners and, to ask for attention, an accent outline. */
@Composable
internal fun CardSurface(
    modifier: Modifier = Modifier,
    outline: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = outline?.let { BorderStroke(2.dp, it) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A person as a circle with their first letter; the name is read next to it, so the circle says nothing. */
@Composable
internal fun Avatar(
    name: String,
    size: Dp = AVATAR_SIZE,
) {
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase().orEmpty(),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * INITIAL_SCALE).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val TILE_SIZE = 48.dp
private val AVATAR_SIZE = 40.dp
private const val INITIAL_SCALE = 0.4f
