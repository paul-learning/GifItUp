package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
import app.gifitup.shared.presentation.onboarding.OriginView

/**
 * Renders the onboarding state and sends the user's actions to the holder; it decides nothing (ADR-0013). Typed text
 * is held with `remember`, never `rememberSaveable`: an invite code must not reach saved state (#17 plan, SEC-3).
 */
@Composable
internal fun OnboardingScreen(
    state: OnboardingState,
    holder: OnboardingStateHolder,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        originOf(state)?.let { OriginBanner(it) }
        when (state) {
            OnboardingState.Starting, OnboardingState.Registering -> CircularProgressIndicator()
            is OnboardingState.EnterInvite -> EnterInvite(state, holder, onOpenSettings)
            is OnboardingState.ChooseUsername -> ChooseUsername(state, holder)
            is OnboardingState.ConfirmServer -> ConfirmServer(state, holder)
            is OnboardingState.FinishPending -> FinishPending(state, holder)
            is OnboardingState.Done -> Done(state)
            is OnboardingState.Blocked -> Text(Texts.blocked(state.reason))
            is OnboardingState.Failed -> Text(Texts.error(state.error))
        }
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
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun EnterInvite(
    state: OnboardingState.EnterInvite,
    holder: OnboardingStateHolder,
    onOpenSettings: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Text("Paste the invite link or code a friend sent you.", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Invite link or code") }, singleLine = true)
    state.problem?.let { Text(Texts.invite(it), color = MaterialTheme.colorScheme.error) }
    // Attacker-chosen text: shown as plain text only, never as a link (#129 SEC-18).
    state.otherOrigin?.let { Text("It is for: $it") }
    Button(onClick = { holder.submitInvite(text) }) { Text("Continue") }
    TextButton(onClick = onOpenSettings) { Text("Server settings") }
}

@Composable
private fun ChooseUsername(
    state: OnboardingState.ChooseUsername,
    holder: OnboardingStateHolder,
) {
    Text("Choose your username.", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = state.username,
        onValueChange = holder::changeUsername,
        label = { Text("Username") },
        singleLine = true,
    )
    state.problem?.let { Text(Texts.username(it), color = MaterialTheme.colorScheme.error) }
    Button(onClick = holder::submitUsername) { Text("Continue") }
}

@Composable
private fun ConfirmServer(
    state: OnboardingState.ConfirmServer,
    holder: OnboardingStateHolder,
) {
    Text("Register as ${state.username} on this server?", style = MaterialTheme.typography.titleMedium)
    Text(state.origin.origin, style = MaterialTheme.typography.bodyLarge)
    Text("Your invite is sent to this server and used up there.")
    Button(onClick = holder::confirmRegistration) { Text("Register") }
    TextButton(onClick = holder::backToUsername) { Text("Back") }
}

@Composable
private fun FinishPending(
    state: OnboardingState.FinishPending,
    holder: OnboardingStateHolder,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    Text("The registration of ${state.username} isn't finished.", style = MaterialTheme.typography.titleMedium)
    state.lastError?.let { Text(Texts.error(it)) }
    Button(onClick = holder::finishPending) { Text("Finish registration") }
    TextButton(onClick = { confirmDiscard = true }) { Text("Start over") }
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
private fun Done(state: OnboardingState.Done) {
    Text("Welcome, ${state.username}!", style = MaterialTheme.typography.titleMedium)
    if (state.keyPackagesUploaded == false) Text("Some setup is still pending; it finishes the next time you open the app.")
}
