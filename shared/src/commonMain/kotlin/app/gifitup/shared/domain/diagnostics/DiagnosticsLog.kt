package app.gifitup.shared.domain.diagnostics

/**
 * Where the app notes what happened, for the user to share on purpose. [record] never throws and never blocks: diagnostics
 * must not be able to break the app, and what it fails to write is simply not there.
 */
interface DiagnosticsLog {
    fun record(
        event: DiagEvent,
        detail: DiagDetail? = null,
    )

    /** The kept entries, oldest first; a log that can't be read is empty. */
    suspend fun entries(): List<DiagEntry>

    /** Forgets every entry; the clearing itself is the log's first entry afterwards. */
    suspend fun clear()

    /**
     * Ends the log for this process: a change already running finishes first, then nothing is read-modified-written again. The
     * wipe calls it before it deletes the store, so no event recreates the store or puts old entries back after it (security
     * review SEC-13). The next process starts a new log.
     */
    suspend fun stop()

    companion object {
        /** For the places that have no log (tests, the dev CLI). */
        val None: DiagnosticsLog =
            object : DiagnosticsLog {
                override fun record(
                    event: DiagEvent,
                    detail: DiagDetail?,
                ) = Unit

                override suspend fun entries(): List<DiagEntry> = emptyList()

                override suspend fun clear() = Unit

                override suspend fun stop() = Unit
            }
    }
}
