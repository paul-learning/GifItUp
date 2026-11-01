package app.gifitup.cli

import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.auth.OpenHistoryKey
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.messages.EncryptedMessageStore
import app.gifitup.shared.domain.messages.HistoryLimits
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.MessageIntake
import kotlinx.coroutines.sync.Mutex
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens the CLI's sealed history (ADR-0017) the way the app does: the key by [OpenHistoryKey] (made only over a record store
 * that holds nothing, never replaced), the cipher over it, the encrypted store over `<state dir>/history`. Failures are
 * fixed lines: a missing, damaged or unopenable key refuses (`EXIT_REFUSED`), storage and cipher faults fail
 * (`EXIT_FAILURE`). The state dir's file lock is held for the whole command, so one process opens the history at a time.
 */
internal class CliHistory(
    private val state: StateDir,
    private val origin: String,
    private val random: SecureRandomSource,
    private val ciphers: HistoryCipherFactory,
) {
    suspend fun <T> use(block: suspend (EncryptedMessageStore) -> T): T {
        val store = open()
        try {
            return block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    private suspend fun open(): EncryptedMessageStore =
        try {
            build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalStateException) {
            throw closedLine(e.problem == LocalStateException.Problem.STORAGE, e)
        } catch (e: MlsException) {
            throw CliError("the history can't be opened (cipher)", EXIT_FAILURE).also { it.initCause(e) }
        }

    private fun closedLine(
        storage: Boolean,
        cause: Exception,
    ): CliError = (if (storage) CliError(STORAGE_LINE, EXIT_FAILURE) else CliError(KEY_LINE, EXIT_REFUSED)).also { it.initCause(cause) }

    @Suppress("TooGenericExceptionCaught") // If the store's construction throws, the cipher (and its key) is closed first.
    private suspend fun build(): EncryptedMessageStore {
        val key = OpenHistoryKey(state.historyKeys, state.identities, origin, state.records, random, Mutex())()
        val cipher =
            try {
                ciphers.create(key, origin)
            } finally {
                key.fill(0)
            }
        return try {
            EncryptedMessageStore(cipher, state.records, HistoryLimits())
        } catch (e: Throwable) {
            cipher.close()
            throw e
        }
    }

    private companion object {
        const val KEY_LINE = "the history can't be opened (key)"
        const val STORAGE_LINE = "the history can't be opened (storage)"
    }
}

/** Stores a message, then prints its line. A store failure is not caught: it goes to the engine, and nothing is printed. */
internal class StoringSink(
    private val intake: MessageIntake,
    private val out: PrintStream,
) : IncomingSink {
    override suspend fun deliver(message: DeliveredMessage) {
        intake.deliver(message)
        messageLine(message)?.let(out::println)
    }
}

/** The fixed line for a history that fails while a command reads it. */
internal fun historyError(e: HistoryStoreException): CliError =
    when (e.kind) {
        HistoryStoreException.Kind.KEY -> CliError("the history can't be read (key)", EXIT_REFUSED)
        HistoryStoreException.Kind.STORAGE -> CliError("the history can't be read (storage)", EXIT_FAILURE)
        else -> CliError("the history can't be read", EXIT_FAILURE)
    }

/** Deletes this state dir's history for a new registration: the records first, then the key, like the app. */
internal suspend fun StateDir.clearHistory() {
    try {
        records.deleteAll()
    } catch (_: RecordStoreException) {
        throw CliError("the old history of this state dir can't be deleted", EXIT_FAILURE)
    }
    historyKeys.clear()
}
