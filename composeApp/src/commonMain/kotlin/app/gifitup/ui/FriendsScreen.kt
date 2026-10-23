package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gifitup.GlyphSupport
import app.gifitup.shared.data.crypto.FingerprintEmoji
import app.gifitup.shared.presentation.contacts.ContactsStateHolder
import app.gifitup.shared.presentation.contacts.FriendStatus
import app.gifitup.shared.presentation.contacts.FriendView
import app.gifitup.shared.presentation.contacts.ReviewView

/**
 * Renders the friends state and sends the user's actions to the holder; it decides nothing (ADR-0013, ADR-0014). The
 * screen shows public keys as emojis and usernames, so it needs no screen-capture protection; it never shows an
 * account id, and it offers no way to copy or share the emojis (an exchange in a chat defeats the comparison). Leaving
 * the screen drops its state.
 */
@Composable
internal fun FriendsScreen(
    holder: ContactsStateHolder,
    glyphs: GlyphSupport,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(holder) { holder.open() }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Friends", style = MaterialTheme.typography.titleMedium)
        if (state.busy) CircularProgressIndicator()
        state.problem?.let { Text(FriendTexts.problem(it), color = MaterialTheme.colorScheme.error) }
        state.own?.let { own ->
            Text(FriendTexts.OWN_TITLE, style = MaterialTheme.typography.titleSmall)
            Text(FriendTexts.OWN_HINT)
            EmojiBlock(own, glyphs)
        }
        if (state.loaded &&
            state.friends.isEmpty()
        ) {
            Text("No friends yet. People you invite, and the person who invited you, show up here.")
        }
        state.friends.forEach { friend -> Friend(friend, glyphs, canReview = !state.busy, onReview = { holder.review(friend.ref) }) }
        TextButton(onClick = onBack) { Text("Back") }
    }
    state.review?.let { Review(it, glyphs, state.busy, holder) }
}

@Composable
private fun Friend(
    friend: FriendView,
    glyphs: GlyphSupport,
    canReview: Boolean,
    onReview: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(friend.username, style = MaterialTheme.typography.titleSmall)
        Text(FriendTexts.status(friend.status))
        friend.reason?.let { Text(FriendTexts.reviewTitle(it), color = MaterialTheme.colorScheme.error) }
        friend.devices.forEach { EmojiBlock(it, glyphs) }
        if (friend.status == FriendStatus.CHANGED) Button(onClick = onReview, enabled = canReview) { Text("Review") }
    }
}

/**
 * The review of one blocked friend, drawn only from the [ReviewView] the holder captured: a refresh can't swap the
 * emojis under the user, and the confirmation is forgotten whenever the review is rebuilt ([ReviewView.version]).
 */
@Composable
private fun Review(
    review: ReviewView,
    glyphs: GlyphSupport,
    busy: Boolean,
    holder: ContactsStateHolder,
) {
    var confirmed by remember(review.version) { mutableStateOf(false) }
    val allGlyphs = (review.pinned + review.observed + review.resulting).flatten().all { glyphs.supports(it.emoji) == true }
    val button = FriendTexts.trustButton(review.reason)
    AlertDialog(
        onDismissRequest = holder::cancelReview,
        title = { Text(FriendTexts.reviewTitle(review.reason)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(review.username + if (review.observedUsername != review.username) " → " + review.observedUsername else "")
                Text(FriendTexts.reviewBody(review.reason))
                if (review.changes > 0) Text(FriendTexts.CHANGED_NOTE, color = MaterialTheme.colorScheme.error)
                if (review.changes >= 2) Text(FriendTexts.KEEPS_CHANGING, color = MaterialTheme.colorScheme.error)
                if (review.pinned.isNotEmpty()) {
                    Text("Saved before:", style = MaterialTheme.typography.titleSmall)
                    review.pinned.forEach { EmojiBlock(it, glyphs) }
                }
                Text("Seen now:", style = MaterialTheme.typography.titleSmall)
                review.observed.forEach { EmojiBlock(it, glyphs) }
                if (button != null && review.canTrust) {
                    Text("After you trust it, these are saved:", style = MaterialTheme.typography.titleSmall)
                    review.resulting.forEach { EmojiBlock(it, glyphs) }
                    Text(FriendTexts.ALL_TWELVE)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text(FriendTexts.confirmation(allGlyphs))
                    }
                }
            }
        },
        confirmButton = {
            if (button != null) {
                TextButton(onClick = { holder.trust(confirmed) }, enabled = review.canTrust && confirmed && !busy) { Text(button) }
            }
        },
        dismissButton = { TextButton(onClick = holder::cancelReview) { Text("Cancel") } },
    )
}

/**
 * One fingerprint: all 12 emojis as one block in a fixed order, three rows of four, each with its name as visible text
 * (a missing glyph draws a box, so the name is what is compared). A notice stays up unless every glyph is known to be
 * drawn (ADR-0014).
 */
@Composable
private fun EmojiBlock(
    cells: List<FingerprintEmoji>,
    glyphs: GlyphSupport,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cells.any { glyphs.supports(it.emoji) != true }) {
            Text(FriendTexts.GLYPH_NOTICE, color = MaterialTheme.colorScheme.error)
        }
        cells.chunked(CELLS_PER_ROW).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(cell.emoji, fontSize = 28.sp)
                        Text(cell.name, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private const val CELLS_PER_ROW = 4
