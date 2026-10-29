package app.gifitup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gifitup.shared.data.network.InviteState
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.invite.InviteScreenState
import kotlin.time.Instant

/**
 * Renders the invite state and sends the user's actions to the holder; it decides nothing (ADR-0013). While the screen
 * is shown the window is protected from screen capture. The link leaves memory and (best effort) the clipboard when
 * the screen is left. When the app only stops (background, screen off, rotation) it leaves the screen and memory but
 * not the clipboard: the user is probably going to paste it (#17 plan review SEC-8, #142 SEC-1, #143 SEC-1).
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
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        holder.resumed()
                    }

                    Lifecycle.Event.ON_STOP -> {
                        holder.stopped()
                    }

                    else -> {}
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.leave()
            screen.protect(false)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar("Invite a friend", onBack, modifier = Modifier.padding(bottom = 4.dp))
        CardSurface {
            Text("Anyone who has the link can join, once, until it expires. Send it only to the person you mean.")
            PrimaryButton("Create invite", onClick = holder::create, enabled = !state.busy)
            if (state.busy) CircularProgressIndicator()
            state.problem?.let { Text(Texts.invite(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
        }
        state.shown?.let { shown ->
            CardSurface(outline = MaterialTheme.colorScheme.secondary) {
                Text("Your new invite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Expires ${Instant.fromEpochSeconds(shown.expiresAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Not selectable: only the Copy button sets the sensitive, labelled clipboard entry (#143 SEC-2).
                Text(
                    shown.link,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                            .padding(12.dp),
                )
                PrimaryButton("Copy link", onClick = holder::copy)
                if (state.copied) Text(Texts.copied(clearsClipboard), modifier = Modifier.polite())
            }
        }
        Invites(state, holder)
    }
}

@Composable
private fun Invites(
    state: InviteScreenState,
    holder: CreateInviteStateHolder,
) {
    val invites = state.invites ?: return
    Text(
        "Your invites",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    if (invites.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    invites.forEach { invite ->
        CardSurface {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Texts.inviteRow(invite), modifier = Modifier.weight(1f))
                if (invite.status.isOpen()) {
                    TextButton(onClick = { holder.revoke(invite.inviteId) }, enabled = !state.busy) { Text("Revoke") }
                }
            }
        }
    }
}

private fun InviteState.isOpen() = this == InviteState.OPEN
