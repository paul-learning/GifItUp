package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gifitup.shared.data.network.Invite
import app.gifitup.shared.data.network.InviteState
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.invite.InviteScreenState
import kotlin.time.Instant

/**
 * Renders the invite state and sends the user's actions to the holder; it decides nothing (ADR-0013). While the screen
 * is shown the window is protected from screen capture. The link leaves memory and the clipboard when the screen is
 * left, and also when the app stops (goes to the background or the screen turns off): a stolen unlocked phone or the
 * recents list must not offer a live invite (#17 plan review SEC-8, #142 SEC-1).
 */
@Composable
internal fun CreateInviteScreen(
    holder: CreateInviteStateHolder,
    clearsClipboard: Boolean,
    screen: ScreenProtection,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(holder) { holder.refresh() }
    DisposableEffect(holder, lifecycle) {
        screen.protect(true)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) holder.leave() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.leave()
            screen.protect(false)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Invite a friend", style = MaterialTheme.typography.titleMedium)
        Text("Anyone who has the link can join, once, until it expires. Send it only to the person you mean.")
        Button(onClick = holder::create, enabled = !state.busy) { Text("Create invite") }
        if (state.busy) CircularProgressIndicator()
        state.problem?.let { Text(Texts.invite(it), color = MaterialTheme.colorScheme.error) }
        state.shown?.let { shown ->
            Text("Your new invite (expires ${Instant.fromEpochSeconds(shown.expiresAt)}):")
            SelectionContainer { Text(shown.link) }
            Button(onClick = holder::copy) { Text("Copy link") }
            if (state.copied) Text(Texts.copied(clearsClipboard))
        }
        Invites(state, holder)
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun Invites(
    state: InviteScreenState,
    holder: CreateInviteStateHolder,
) {
    val invites = state.invites ?: return
    Text("Your invites", style = MaterialTheme.typography.titleSmall)
    if (invites.isEmpty()) Text("None yet.")
    invites.forEach { invite ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(Texts.inviteRow(invite))
            if (invite.status ==
                InviteState.OPEN
            ) {
                TextButton(onClick = { holder.revoke(invite.inviteId) }, enabled = !state.busy) { Text("Revoke") }
            }
        }
    }
}

internal fun Invite.expiryText(): String = Instant.fromEpochSeconds(expiresAt).toString()
