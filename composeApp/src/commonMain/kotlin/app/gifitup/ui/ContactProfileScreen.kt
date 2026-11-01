package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gifitup.GlyphSupport
import app.gifitup.shared.domain.media.CivilDate
import app.gifitup.shared.presentation.contacts.ContactsStateHolder

/**
 * A contact's profile (#217): the username, what the app has saved of their key (the same card as on the Friends screen: the
 * emojis to compare, a review when the key changed) and the date this device first saw them. Renders state and decides nothing
 * (ADR-0013). A username that is not in the contact book (a friend who is gone) says so instead of showing anything.
 */
@Composable
internal fun ContactProfileScreen(
    username: String,
    holder: ContactsStateHolder,
    glyphs: GlyphSupport,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) { holder.open() }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    val friend = state.friends.firstOrNull { it.username == username }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(ChatTexts.isolated(username), onBack, modifier = Modifier.padding(bottom = 4.dp))
        if (state.busy || !state.loaded) CircularProgressIndicator()
        state.problem?.let { Text(FriendTexts.problem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
        if (friend != null) {
            Friend(friend, glyphs, canReview = !state.busy, onReview = { holder.review(friend.ref) })
            if (friend.knownSince > 0) {
                Text(
                    ProfileTexts.knownSince(dateOf(friend.knownSince)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.loaded) {
            Text(ProfileTexts.NOT_IN_BOOK, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.review?.let { Review(it, glyphs, state.busy, holder) }
}

/** The UTC day of [epochSeconds] (the device's own clock), as `2026-11-01`: the same on every platform and in every locale. */
private fun dateOf(epochSeconds: Long): String {
    val day = CivilDate.ofEpochDays(epochSeconds / SECONDS_PER_DAY)
    return "${day.year}-${day.month.toString().padStart(2, '0')}-${day.day.toString().padStart(2, '0')}"
}

private const val SECONDS_PER_DAY = 86_400L

/** The fixed words of the contact profile. */
internal object ProfileTexts {
    const val NOT_IN_BOOK = "This friend is not in your friends list on this device."
    const val OPEN_PROFILE = "Open profile"

    fun knownSince(date: String): String = "Known since $date (the day this device first saw them)"
}
