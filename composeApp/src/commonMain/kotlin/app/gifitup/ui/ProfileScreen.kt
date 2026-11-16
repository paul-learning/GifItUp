package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gifitup.GlyphSupport
import app.gifitup.shared.presentation.contacts.ContactsStateHolder
import app.gifitup.shared.presentation.onboarding.OriginView

/**
 * The user's own profile (#217): the username, which can't be changed yet, and the own key emojis (the same ones the
 * Friends screen offers), for reading out to a friend. Renders state and decides nothing (ADR-0013).
 */
@Suppress("LongParameterList") // The screen's own pieces, handed down from the one place that owns them.
@Composable
internal fun ProfileScreen(
    username: String,
    server: OriginView,
    holder: ContactsStateHolder,
    glyphs: GlyphSupport,
    protection: ProtectionControls?,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    var showOwn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(holder) { holder.open() }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(ChatTexts.PROFILE, onBack, modifier = Modifier.padding(bottom = 4.dp))
        // The server this account is on, called out while it is not the default (ADR-0013): the list shows only a marker.
        OriginBanner(server)
        CardSurface {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(username, PROFILE_AVATAR)
                Text(ChatTexts.isolated(username), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        state.own?.let { OwnEmojisCard(onShow = { showOwn = true }) }
        protection?.let { ScreenProtectionCard(it) }
    }
    state.own?.takeIf { showOwn }?.let { own -> OwnEmojisDialog(own, glyphs) { showOwn = false } }
}

private val PROFILE_AVATAR = 72.dp

/** The "hide chats from screenshots" switch (#281): on by default, Android only (the caller passes `null` elsewhere). */
@Composable
private fun ScreenProtectionCard(protection: ProtectionControls) {
    val on by protection.on.collectAsState()
    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(ProfileTexts.PROTECT_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    ProfileTexts.PROTECT_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = protection.set)
        }
    }
}
