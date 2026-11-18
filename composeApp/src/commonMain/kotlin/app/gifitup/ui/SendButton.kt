package app.gifitup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The round send button of the caption bar (#154): a purple circle with a drawn arrow, dimmed and inert while [enabled] is false. */
@Composable
internal fun SendButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(SEND_SIZE)
            .clip(CircleShape)
            .background(if (enabled) colors.primary else colors.primary.copy(alpha = DISABLED))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ARROW)) {
            val width = 2.dp.toPx()
            val mid = size.width / 2
            drawLine(colors.onPrimary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), width, StrokeCap.Round)
            drawLine(
                colors.onPrimary,
                Offset(mid + mid / 2, size.height * HEAD_TOP),
                Offset(size.width, size.height / 2),
                width,
                StrokeCap.Round,
            )
            drawLine(
                colors.onPrimary,
                Offset(mid + mid / 2, size.height * HEAD_BOTTOM),
                Offset(size.width, size.height / 2),
                width,
                StrokeCap.Round,
            )
        }
    }
}

private val SEND_SIZE = 44.dp
private val ARROW = 18.dp
private const val DISABLED = 0.4f
private const val HEAD_TOP = 0.15f
private const val HEAD_BOTTOM = 0.85f
