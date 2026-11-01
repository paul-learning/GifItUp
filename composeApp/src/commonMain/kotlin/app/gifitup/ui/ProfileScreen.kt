package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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

/**
 * The user's own profile (#217): the username, which can't be changed yet, and the own key emojis (the same ones the
 * Friends screen offers), for reading out to a friend. Renders state and decides nothing (ADR-0013).
 */
@Composable
internal fun ProfileScreen(
    username: String,
    holder: ContactsStateHolder,
    glyphs: GlyphSupport,
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
        CardSurface {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(username, PROFILE_AVATAR)
                Text(ChatTexts.isolated(username), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        state.own?.let { OwnEmojisCard(onShow = { showOwn = true }) }
    }
    state.own?.takeIf { showOwn }?.let { own -> OwnEmojisDialog(own, glyphs) { showOwn = false } }
}

private val PROFILE_AVATAR = 72.dp
