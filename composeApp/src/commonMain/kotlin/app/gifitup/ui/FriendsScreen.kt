package app.gifitup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
    var showOwn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(holder) { holder.open() }
    DisposableEffect(holder) { onDispose { holder.leave() } }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(FriendTexts.TITLE, onBack, modifier = Modifier.padding(bottom = 4.dp))
        if (state.busy) CircularProgressIndicator()
        state.problem?.let { Text(FriendTexts.problem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
        // The user's own emojis are one tap away, behind the info button: they are for reading out on a call.
        state.own?.let { OwnEmojisCard(onShow = { showOwn = true }) }
        if (state.loaded && state.friends.isEmpty()) Text(FriendTexts.NO_FRIENDS, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.friends.forEach { friend -> Friend(friend, glyphs, canReview = !state.busy, onReview = { holder.review(friend.ref) }) }
    }
    state.own?.takeIf { showOwn }?.let { own -> OwnEmojisDialog(own, glyphs) { showOwn = false } }
    state.review?.let { Review(it, glyphs, state.busy, holder) }
}

/** The user's own key emojis, to read out on a call: a dialog with the emojis and what they are for. */
@Composable
internal fun OwnEmojisDialog(
    own: List<FingerprintEmoji>,
    glyphs: GlyphSupport,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FriendTexts.OWN_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(FriendTexts.OWN_HINT)
                EmojiBlock(own, glyphs)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(FriendTexts.CLOSE) } },
    )
}

@Composable
internal fun OwnEmojisCard(onShow: () -> Unit) {
    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(FriendTexts.CARD_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    FriendTexts.CARD_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconTile(FriendTexts.SHOW_OWN, onShow) { InfoIcon(MaterialTheme.colorScheme.secondary) }
        }
    }
}

/** One friend: avatar, name and one line of status; a tap shows the saved emojis to compare, a changed key asks for a review. */
@Composable
private fun Friend(
    friend: FriendView,
    glyphs: GlyphSupport,
    canReview: Boolean,
    onReview: () -> Unit,
) {
    var expanded by remember(friend.ref) { mutableStateOf(false) }
    val changed = friend.status == FriendStatus.CHANGED
    val colors = MaterialTheme.colorScheme
    val statusColor =
        when (friend.status) {
            FriendStatus.CHANGED -> colors.tertiary
            FriendStatus.SAVED -> colors.secondary
            else -> colors.onSurfaceVariant
        }
    CardSurface(outline = if (changed) colors.tertiary else null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) FriendTexts.HIDE_DETAILS else FriendTexts.SHOW_DETAILS, role = Role.Button) {
                    expanded = !expanded
                }.semantics { stateDescription = if (expanded) FriendTexts.EXPANDED else FriendTexts.COLLAPSED },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(friend.username)
            Column {
                Text(friend.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    FriendTexts.headline(friend.status, friend.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        if (changed) {
            Button(
                onClick = onReview,
                enabled = canReview,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(FriendTexts.REVIEW, fontWeight = FontWeight.Bold) }
        }
        if (expanded) {
            Text(FriendTexts.status(friend.status), style = MaterialTheme.typography.bodyMedium)
            friend.reason?.let { Text(FriendTexts.reviewTitle(it), color = colors.tertiary) }
            friend.devices.forEach { EmojiBlock(it, glyphs) }
        }
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
                    Text(FriendTexts.allTwelve(allGlyphs))
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
