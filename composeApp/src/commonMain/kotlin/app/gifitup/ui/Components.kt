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
import androidx.compose.ui.geometry.Size
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
@Suppress("LongParameterList") // One slot for each part of the bar.
@Composable
internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    backLabel: String = ChatTexts.BACK,
    backEnabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onBack != null) {
            IconTile(backLabel, onBack, enabled = backEnabled) { color -> Chevron(color) }
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
    enabled: Boolean = true,
    icon: @Composable (Color) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
    Box(
        Modifier
            .size(TILE_SIZE)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon(color) }
}

@Composable
internal fun Chevron(
    color: Color,
    pointRight: Boolean = false,
) {
    Canvas(Modifier.size(20.dp)) {
        val width = 2.5.dp.toPx()

        // Drawn pointing left, mirrored for the right.
        fun x(fraction: Float) = size.width * if (pointRight) 1f - fraction else fraction
        drawLine(color, Offset(x(0.65f), size.height * 0.15f), Offset(x(0.3f), size.height * 0.5f), width, StrokeCap.Round)
        drawLine(color, Offset(x(0.3f), size.height * 0.5f), Offset(x(0.65f), size.height * 0.85f), width, StrokeCap.Round)
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

/** Two people: a head and shoulders, twice, the second behind the first. */
@Composable
internal fun PeopleIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val width = 2.dp.toPx()
        listOf(0.38f to 1f, 0.72f to 0.7f).forEach { (x, alpha) ->
            val c = color.copy(alpha = alpha)
            drawCircle(c, radius = size.width * 0.14f, center = Offset(size.width * x, size.height * 0.34f), style = Stroke(width))
            drawArc(
                c,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * (x - 0.2f), size.height * 0.6f),
                size = Size(size.width * 0.4f, size.height * 0.5f),
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
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
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Box(
        Modifier
            .size(size)
            .background(color, CircleShape)
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

/** The first few other members as overlapping avatars, then how many more; decorative, the members are named in the title. */
@Composable
internal fun MemberAvatars(names: List<String>) {
    val shown = names.take(MAX_MEMBER_AVATARS)
    val more = names.size - shown.size
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(AVATAR_OVERLAP)) {
        shown.forEach { Avatar(it, MEMBER_AVATAR_SIZE) }
        if (more > 0) {
            Box(
                Modifier.size(MEMBER_AVATAR_SIZE).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("+$more", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }
    }
}

/** The round "+" button of the design. [enabled] false draws it dimmed and inert; [label] is what a screen reader says. */
@Composable
internal fun PlusButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fill = if (enabled) colors.primary else colors.primary.copy(alpha = DISABLED_ALPHA)
    Box(
        Modifier
            .size(PLUS_SIZE)
            .background(fill, CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(PLUS_ICON)) {
            val width = 3.dp.toPx()
            drawLine(colors.onPrimary, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), width, StrokeCap.Round)
            drawLine(colors.onPrimary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), width, StrokeCap.Round)
        }
    }
}

private val MEMBER_AVATAR_SIZE = 36.dp
private val AVATAR_OVERLAP = (-10).dp
private val PLUS_SIZE = 72.dp
private val PLUS_ICON = 24.dp
private const val MAX_MEMBER_AVATARS = 3
private const val DISABLED_ALPHA = 0.4f
