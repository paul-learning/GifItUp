package app.gifitup.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.gifitup.ui.LogExport
import java.io.IOException

/**
 * Android's way out for the diagnostics report, both by the user's own tap. [share] opens the system chooser (a plain-text
 * `EXTRA_TEXT`, no file, no stream grant, no extra permission); [save] asks [pickFile] for a file the user chooses
 * (the Storage Access Framework), and [write] puts the text into it, replacing what was there. The text is never logged and
 * is kept only between the tap and the user's choice.
 */
internal class AndroidLogExport(
    private val context: Context,
    private val pickFile: (name: String) -> Unit,
) : LogExport {
    override val canShare = true

    override fun share(text: String) {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "GifItUp log")
                .putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (ignored: ActivityNotFoundException) {
            // No app takes text: the user can still save or copy it.
        }
    }

    override fun save(text: String) {
        pending = text
        pickFile(LogExport.FILE_NAME)
    }

    /** The user picked [uri] (or none): "wt" truncates, so a longer file that was there leaves nothing of its old content. */
    fun write(uri: Uri?) {
        val text = pending
        pending = null
        if (uri == null || text == null) return
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (ignored: IOException) {
            // The user chose a place that refused; nothing is kept of the text.
        } catch (ignored: SecurityException) {
            // The same.
        }
    }

    private companion object {
        // Process-wide, not per Activity: a rotation while the file picker is open makes a new Activity (and a new export), and
        // the picker's answer must still find the text (security review SEC-15). Cleared by the answer.
        @Volatile
        var pending: String? = null
    }
}
