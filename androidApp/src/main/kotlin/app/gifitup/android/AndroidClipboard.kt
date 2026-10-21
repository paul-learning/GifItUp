package app.gifitup.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import app.gifitup.shared.presentation.invite.SensitiveClipboard

/**
 * The clipboard for an invite link (#17 plan review SEC-8). From Android 13 the entry is marked sensitive, so the
 * system hides it from the paste preview and the clipboard overlay. [clearCopied] checks the entry's label, which is
 * all it may read without the clipboard's content, and removes the entry only if the label is ours. It is best
 * effort: a process kill can leave the link there, and an app that isn't in the foreground may not be allowed to
 * clear (Android 10 and up). Holds the application context only, never an Activity: the ViewModels that use it
 * outlive one.
 */
internal class AndroidClipboard(
    context: Context,
) : SensitiveClipboard {
    private val manager = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override val clearsCopied = true

    override suspend fun copy(text: String): Boolean {
        val clip = ClipData.newPlainText(LABEL, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        return try {
            manager.setPrimaryClip(clip)
            true
        } catch (ignored: RuntimeException) {
            false
        }
    }

    override fun clearCopied() {
        try {
            if (manager.primaryClipDescription?.label?.toString() != LABEL) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (ignored: RuntimeException) {
            // Best effort: the platform may refuse while the app isn't in the foreground.
        }
    }

    private companion object {
        const val LABEL = "GifItUp invite"
    }
}
