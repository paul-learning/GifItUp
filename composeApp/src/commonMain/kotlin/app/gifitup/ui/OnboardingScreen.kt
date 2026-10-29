package app.gifitup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
import app.gifitup.shared.presentation.onboarding.OriginView

/**
 * Renders the onboarding state and sends the user's actions to the holder; it decides nothing (ADR-0013). Typed text
 * is held with `remember`, never `rememberSaveable`: an invite code must not reach saved state (#17 plan, SEC-3).
 */
@Suppress("LongParameterList") // The state, its holder and one callback per destination of this screen.
@Composable
internal fun OnboardingScreen(
    state: OnboardingState,
    holder: OnboardingStateHolder,
    current: OriginView,
    onOpenSettings: () -> Unit,
    onFriends: () -> Unit,
    onChats: () -> Unit,
    onInvite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Brand()
        // On every screen while the server is not the default; on the steps that send to it, always (ADR-0013).
        val shown = originOf(state) ?: current.takeIf { it.custom }
        shown?.let { OriginBanner(it) }
        when (state) {
            OnboardingState.Starting, OnboardingState.Registering -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            is OnboardingState.EnterInvite -> {
                EnterInvite(state, holder, onOpenSettings)
            }

            is OnboardingState.ChooseUsername -> {
                ChooseUsername(state, holder)
            }

            is OnboardingState.ConfirmServer -> {
                ConfirmServer(state, holder)
            }

            is OnboardingState.FinishPending -> {
                FinishPending(state, holder)
            }

            is OnboardingState.Done -> {
                Done(state, onChats, onFriends, onInvite)
            }

            is OnboardingState.Blocked -> {
                CardSurface { Text(Texts.blocked(state.reason), color = MaterialTheme.colorScheme.error) }
            }

            is OnboardingState.Failed -> {
                CardSurface { Text(Texts.error(state.error), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** The name of the app and what it is, at the top of every step. */
@Composable
private fun Brand() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "GifItUp",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.semantics { heading() },
        )
        Text(Texts.TAGLINE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun originOf(state: OnboardingState): OriginView? =
    when (state) {
        is OnboardingState.EnterInvite -> state.origin
        is OnboardingState.ChooseUsername -> state.origin
        is OnboardingState.ConfirmServer -> state.origin
        else -> null
    }

/** The server, always as canonical ASCII text; a custom one is called out (ADR-0013). */
@Composable
private fun OriginBanner(origin: OriginView) {
    val text = if (origin.custom) "Custom server: ${origin.origin}" else "Server: ${origin.origin}"
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (origin.custom) colors.tertiary else colors.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainer, MaterialTheme.shapes.medium)
                .then(if (origin.custom) Modifier.border(BorderStroke(1.dp, colors.tertiary), MaterialTheme.shapes.medium) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun EnterInvite(
    state: OnboardingState.EnterInvite,
    holder: OnboardingStateHolder,
    onOpenSettings: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    CardSurface {
        Text("Paste the invite link or code a friend sent you.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // No autocorrect and a password-type keyboard, so keyboard apps don't learn the code (#131 SEC-22). Not masked.
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Invite link or code") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        )
        state.problem?.let { Text(Texts.invite(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
        // Attacker-chosen text: shown as plain text only, never as a link (#129 SEC-18).
        state.otherOrigin?.let { Text("It is for: $it") }
        PrimaryButton("Continue", onClick = { holder.submitInvite(text) })
    }
    SecondaryButton("Server settings", onOpenSettings)
}

@Composable
private fun ChooseUsername(
    state: OnboardingState.ChooseUsername,
    holder: OnboardingStateHolder,
) {
    CardSurface {
        Text("Choose your username.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = state.username,
            onValueChange = holder::changeUsername,
            label = { Text("Username") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        state.problem?.let { Text(Texts.username(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
        PrimaryButton("Continue", onClick = holder::submitUsername)
    }
}

@Composable
private fun ConfirmServer(
    state: OnboardingState.ConfirmServer,
    holder: OnboardingStateHolder,
) {
    CardSurface {
        Text("Register as ${state.username} on this server?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(state.origin.origin, style = MaterialTheme.typography.bodyLarge)
        Text("Your invite is sent to this server and used up there.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton("Register", onClick = holder::confirmRegistration)
    }
    SecondaryButton("Back", holder::backToUsername)
}

@Composable
private fun FinishPending(
    state: OnboardingState.FinishPending,
    holder: OnboardingStateHolder,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    CardSurface {
        Text(
            "The registration of ${state.username} isn't finished.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        state.lastError?.let { Text(Texts.error(it), color = MaterialTheme.colorScheme.error) }
        PrimaryButton("Finish registration", onClick = holder::finishPending)
    }
    SecondaryButton("Start over", onClick = { confirmDiscard = true })
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Start over?") },
            text = {
                Text(
                    "This deletes the key of the unfinished registration for good. If the server did create the " +
                        "account, it can't be used any more, and you need a new invite.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    holder.discardPending(confirmed = true)
                }) { Text("Delete and start over") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Done(
    state: OnboardingState.Done,
    onChats: () -> Unit,
    onFriends: () -> Unit,
    onInvite: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(state.username, 48.dp)
        Text("Welcome, ${state.username}!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    ActionCard(ChatTexts.TITLE, "Your conversations", onChats)
    ActionCard("Invite a friend", "A one-time link for someone you know", onInvite)
    ActionCard("Friends", "Their keys, and the emojis to compare", onFriends)
    if (state.keyPackagesUploaded == false) {
        Text(
            "Some setup is still pending; it finishes the next time you open the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
