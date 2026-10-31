package app.gifitup.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * "Reset chat history…": offered only when the history cannot be opened and only a reset helps. Nothing happens before the
 * confirmation, which says what is lost (the saved messages on this device) and what is not (the account, the chats, the
 * friends' own copies). The deletion is the holder's.
 */
@Composable
internal fun ResetHistoryButton(onConfirm: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) { Text(ChatTexts.RESET_HISTORY, color = MaterialTheme.colorScheme.error) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(ChatTexts.RESET_TITLE) },
            text = { Text(ChatTexts.RESET_BODY) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onConfirm()
                    },
                ) { Text(ChatTexts.RESET_CONFIRM, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(ChatTexts.RESET_CANCEL) } },
        )
    }
}
