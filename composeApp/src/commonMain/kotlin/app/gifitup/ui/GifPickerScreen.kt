package app.gifitup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.gif.GifTile
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.GifTile as LoadedTile

/**
 * The GIF picker (#23): a search field whose placeholder is the provider's required "Search KLIPY", trending when it is
 * empty, a grid of previews in the provider's order, the privacy note, and calm failure states with "Try again" (nothing
 * retries by itself). Renders the holder's state and forwards actions; it decides nothing (ADR-0013). Nothing the provider
 * sends is shown as text: a result that failed the rules is an inert tile.
 */
@Composable
internal fun GifPickerScreen(
    parts: PickerParts,
    onClose: () -> Unit,
    onPick: (GifReference) -> Unit,
) {
    val picker = parts.picker
    val state by picker.state.collectAsState()
    LaunchedEffect(picker) { picker.open() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTopBar(title = GifPickerTexts.TITLE, onBack = onClose, backLabel = GifPickerTexts.CLOSE)
        OutlinedTextField(
            value = state.query,
            onValueChange = picker::onQuery,
            placeholder = { Text(GifPickerTexts.SEARCH_PLACEHOLDER) },
            label = { Text(GifPickerTexts.SEARCH_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(GifPickerTexts.PRIVACY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (state.query.isBlank()) GifPickerTexts.TRENDING else GifPickerTexts.RESULTS,
            style = MaterialTheme.typography.titleSmall,
        )
        Results(parts, onPick)
    }
}

@Composable
private fun Results(
    parts: PickerParts,
    onPick: (GifReference) -> Unit,
) {
    val picker = parts.picker
    val state by picker.state.collectAsState()
    val previews by parts.previews.tiles.collectAsState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.tiles) { index, tile ->
            when (tile) {
                is GifTile.Item -> PreviewCell(parts, index, tile, previews, onPick)
                GifTile.Placeholder -> InertCell()
            }
            if (index == state.tiles.lastIndex) LaunchedEffect(state.tiles.size) { picker.loadMore() }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Status(
                    state.loading,
                    state.loaded,
                    state.tiles.isEmpty(),
                    state.failure?.let { GifPickerTexts.failure(it, state.retryAfterSeconds) },
                ) {
                    picker.retry()
                }
            }
        }
    }
}

/** What goes under the grid: loading, nothing found, or a failure with "Try again". Only fixed words. */
@Composable
private fun Status(
    loading: Boolean,
    loaded: Boolean,
    empty: Boolean,
    failure: String?,
    onRetry: () -> Unit,
) {
    when {
        failure != null -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.polite())
                TextButton(onClick = onRetry) { Text(GifPickerTexts.TRY_AGAIN) }
            }
        }

        loading -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text(GifPickerTexts.LOADING, style = MaterialTheme.typography.bodySmall, modifier = Modifier.polite())
            }
        }

        loaded && empty -> {
            Text(GifPickerTexts.NOTHING_FOUND, modifier = Modifier.polite())
        }
    }
}

/** One result: its preview loads like a chat bubble's, and a tap on a loaded one picks its `send` reference. */
@Composable
private fun PreviewCell(
    parts: PickerParts,
    index: Int,
    tile: GifTile.Item,
    previews: Map<KeyBytes, LoadedTile>,
    onPick: (GifReference) -> Unit,
) {
    val key = remember(tile) { KeyBytes(tile.preview.url.encodeToByteArray()) }
    DisposableEffect(key) {
        parts.previews.show(key, tile.preview)
        onDispose { parts.previews.hide(key) }
    }
    val loaded = previews[key]
    val pick =
        if (loaded is LoadedTile.Ready) {
            Modifier.clickable(onClickLabel = GifPickerTexts.CHOOSE, role = Role.Button) {
                onPick(tile.send)
            }
        } else {
            Modifier
        }
    // Not the provider's slug or title: a fixed label with the position, so no provider text reaches the screen reader.
    Box(pick.semantics { }) {
        GifTileView(loaded, tile.preview, "GIF ${index + 1}") { parts.previews.tap(key, tile.preview) }
    }
}

@Composable
private fun InertCell() {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        Text(GifPickerTexts.PLACEHOLDER, style = MaterialTheme.typography.bodySmall)
    }
}

private val CELL_MIN_WIDTH = 140.dp
