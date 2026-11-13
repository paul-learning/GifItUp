package app.gifitup

import app.gifitup.ui.LogExport

/**
 * The browser's way out for the diagnostics report: a download of a plain-text file with a fixed name. The text never goes into
 * markup (a Blob of type `text/plain`, so it is never parsed as HTML), and the object URL is revoked at once.
 */
internal object WebLogExport : LogExport {
    override val canShare = false

    override fun share(text: String) = Unit

    override fun save(text: String) = download(text, LogExport.FILE_NAME)
}

@Suppress("UnusedParameter") // Used by name inside the js() code.
private fun download(
    text: String,
    name: String,
): Unit =
    js(
        """{
            const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
            const link = document.createElement('a');
            link.href = url;
            link.download = name;
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 0);
        }""",
    )
