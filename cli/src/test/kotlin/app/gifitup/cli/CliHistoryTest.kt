package app.gifitup.cli

import app.gifitup.mls.UniffiHistoryCipherFactory
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.MessageBody
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CLI's sealed history over the real cipher (host library) and a real state dir. */
class CliHistoryTest {
    private val dir = Files.createTempDirectory("cli-history-").resolve("state")
    private val state = StateDir(dir)
    private val origin = "https://e2e.invalid"
    private val group = KeyBytes(ByteArray(16) { 1 })
    private val message = ChatMessage(group, KeyBytes(ByteArray(32) { 2 }), "ann", 4, MessageBody.UpdateNeeded, null, 1L)

    private fun history() = CliHistory(state, origin, { size -> ByteArray(size) { 7 } }, UniffiHistoryCipherFactory)

    private suspend fun registered() =
        state.identities.save(StoredIdentity(origin, "ann", byteArrayOf(1), deviceId = "0f8fad5b-d9cb-469f-a165-70867728950e"))

    @Test
    fun theKeyIsMadeOnceAndTheMessagesComeBackInANewOpen() =
        runBlocking {
            state.lock().use {
                registered()
                history().use { assertTrue(it.insert(message)) }
                val key = Files.readAllBytes(dir.resolve("history-key"))
                assertEquals(32, key.size)
                history().use { store ->
                    assertEquals(1, store.messages(group).size)
                    assertFalse(store.insert(message), "the same message is stored once")
                }
                assertContentEquals(key, Files.readAllBytes(dir.resolve("history-key")), "the key was not replaced")
            }
        }

    @Test
    fun aMissingOrDamagedKeyRefusesAndNothingIsMade() =
        runBlocking {
            state.lock().use {
                registered()
                history().use { it.insert(message) }
                Files.delete(dir.resolve("history-key"))
                assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { history().use { } }.exit)
                assertFalse(Files.exists(dir.resolve("history-key")), "records without a key never make a key")
                Files.write(dir.resolve("history-key"), ByteArray(33))
                assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { history().use { } }.exit)
                assertEquals(33L, Files.size(dir.resolve("history-key")))
            }
        }

    @Test
    fun aNewRegistrationStartsAClearHistory() =
        runBlocking {
            state.lock().use {
                registered()
                history().use { it.insert(message) }
                state.clearHistory()
                assertFalse(Files.exists(dir.resolve("history-key")))
                state.identities.save(StoredIdentity(origin, "ann", byteArrayOf(1), deviceId = "0f8fad5b-d9cb-469f-a165-70867728950e"))
                history().use { assertEquals(emptyList(), it.messages(group)) }
            }
        }
}
