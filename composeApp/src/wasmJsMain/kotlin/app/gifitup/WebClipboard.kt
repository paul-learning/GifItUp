package app.gifitup

import app.gifitup.shared.presentation.invite.SensitiveClipboard
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Promise

/**
 * The browser clipboard for an invite link (#17). It can neither mark an entry sensitive nor take it back: reading the
 * clipboard to check what is there needs the user's permission, and clearing blindly would wipe something else they
 * copied since. So [clearCopied] does nothing and [clearsCopied] says so, and the screen tells the user. The link is
 * single-use and expires (ADR-0010).
 */
internal object WebClipboard : SensitiveClipboard {
    override val clearsCopied = false

    @Suppress("TooGenericExceptionCaught") // A denied permission or a missing API is a refusal; no message is kept.
    override suspend fun copy(text: String): Boolean =
        try {
            writeText(text).await<JsAny?>()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            false
        }

    override fun clearCopied() = Unit
}

@Suppress("UnusedParameter") // Used by name inside the js() code.
private fun writeText(text: String): Promise<JsAny?> = js("navigator.clipboard.writeText(text)")
