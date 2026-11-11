package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

// The two ends of the chat list's top bar (#154): the burger menu on the left, the profile button on the right.

/** The top bar's right side: a marker while the server is not the default, and the user's initial; both open the Profile. */
@Composable
internal fun TopBarActions(
    customServer: Boolean,
    menu: MenuActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (customServer) {
            // Text next to the icon, so the cue does not depend on colour alone (SEC-1 of the review).
            Text(
                ChatTexts.CUSTOM_SERVER_SHORT,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            IconTile(ChatTexts.CUSTOM_SERVER, menu.onProfile) { WarningIcon(MaterialTheme.colorScheme.tertiary) }
        }
        if (menu.username.isNotBlank()) ProfileButton(menu.username, ChatTexts.PROFILE, menu.onProfile)
    }
}

/** The burger menu at the top left: invite a friend and friends (the profile is the initial on the right). */
@Composable
internal fun BurgerMenu(actions: MenuActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconTile(ChatTexts.MENU, { open = true }) { MenuIcon(it) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                ChatTexts.FRIENDS to actions.onFriends,
                ChatTexts.INVITE to actions.onInvite,
            ).forEach { (label, go) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    go()
                })
            }
        }
    }
}
