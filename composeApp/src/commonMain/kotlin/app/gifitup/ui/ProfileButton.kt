package app.gifitup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The user's own initial as a small circle that opens their profile; 48 dp to tap, [label] is what a screen reader says. */
@Composable
internal fun ProfileButton(
    username: String,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(TOUCH_SIZE)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Avatar(username, PROFILE_BUTTON_AVATAR, MaterialTheme.colorScheme.secondaryContainer) }
}

private val PROFILE_BUTTON_AVATAR = 32.dp

private val TOUCH_SIZE = 48.dp
