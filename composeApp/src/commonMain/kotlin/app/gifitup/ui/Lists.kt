package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The list pieces of the slicker look (#154): plain text tabs and quiet section titles, shared by the screens.

/**
 * Plain text tabs: the chosen one is bright with a short purple line under it. A tab is a 48 dp touch target and a selectable
 * with the tab role. [enabled] false makes the row inert (a change is refused while something is being made).
 */
@Composable
internal fun <T> TextTabs(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TAB_GAP)) {
        options.forEach { (choice, label) ->
            val chosen = selected == choice
            Column(
                Modifier
                    .defaultMinSize(minHeight = TAB_HEIGHT)
                    .minimumInteractiveComponentSize()
                    .selectable(selected = chosen, enabled = enabled, role = Role.Tab, onClick = { onSelect(choice) }),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = (if (chosen) colors.onSurface else colors.onSurfaceVariant).copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(width = TAB_LINE, height = 2.dp)
                        .background(if (chosen) colors.primary else Color.Transparent),
                )
            }
        }
    }
}

/** A small quiet title above a list ("Members", "Add friends"); announced as a heading. */
@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}

private const val DISABLED_ALPHA = 0.4f
private val TAB_HEIGHT = 36.dp
private val TAB_LINE = 28.dp
private val TAB_GAP = 24.dp
