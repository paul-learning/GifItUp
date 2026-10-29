package app.gifitup.mls

import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The platform's real [HistoryCipherFactory] (uniffi on JVM, wasm-bindgen in the browser). */
expect suspend fun platformHistoryCipherFactory(): HistoryCipherFactory

/**
 * The history cipher through the bindings (ADR-0017), on the JVM and in headless Chrome. The Rust rules themselves are
 * tested in `crypto/`; this checks that the record format, the names and the typed errors survive the bindings.
 */
class HistoryCipherContractTest {
    private val origin = "https://chat.example.test"
    private val group = ByteArray(16) { 1 }
    private val reference = ByteArray(32) { 2 }

    private fun key(byte: Int = 7) = ByteArray(KEY_SIZE) { byte.toByte() }

    private suspend fun cipher(byte: Int = 7): HistoryCipher = platformHistoryCipherFactory().create(key(byte), origin)

    private fun assertCode(
        code: MlsErrorCode,
        block: () -> Unit,
    ) {
        val e = assertFailsWith<MlsException> { block() }
        assertEquals(code, e.code)
        assertNull(e.message, "no detail crosses the FFI")
        assertNull(e.cause)
    }

    @Test
    fun aSealedRecordOpensToWhatWasSealed() =
        runTest {
            cipher().use { c ->
                val name = c.messageName(group, reference)
                assertEquals(NAME_SIZE, name.size)
                val plaintext = ByteArray(900) { (it % 251).toByte() }
                val sealed = c.seal(name, plaintext)
                assertContentEquals(plaintext, c.open(name, sealed))
                assertEquals(sealed.size, c.seal(name, byteArrayOf(1)).size, "the same bucket: the size says nothing finer")
            }
        }

    @Test
    fun namesAreStableDistinctAndDependOnTheKey() =
        runTest {
            cipher().use { a ->
                cipher(8).use { b ->
                    assertContentEquals(a.groupName(group), a.groupName(group))
                    assertNotEquals(a.groupName(group).toList(), a.readMarkName(group).toList())
                    assertNotEquals(a.messageName(group, reference).toList(), a.groupName(group).toList())
                    assertNotEquals(a.groupName(group).toList(), b.groupName(group).toList())
                }
            }
        }

    @Test
    fun anotherKeyNameOrOriginAndAChangedByteAreHistoryAuth() =
        runTest {
            val factory = platformHistoryCipherFactory()
            cipher().use { c ->
                val name = c.messageName(group, reference)
                val sealed = c.seal(name, "secret".encodeToByteArray())
                cipher(8).use { other -> assertCode(MlsErrorCode.HistoryAuth) { other.open(name, sealed) } }
                factory.create(key(), "https://other.example.test").use { other ->
                    assertCode(MlsErrorCode.HistoryAuth) { other.open(name, sealed) }
                }
                assertCode(MlsErrorCode.HistoryAuth) { c.open(c.messageName(group, ByteArray(32) { 3 }), sealed) }
                val changed = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
                assertCode(MlsErrorCode.HistoryAuth) { c.open(name, changed) }
                // A typed error leaves the cipher usable.
                assertContentEquals("secret".encodeToByteArray(), c.open(name, sealed))
            }
        }

    @Test
    fun sizesAndHeadersNoSealMakesAreHistoryInvalid() =
        runTest {
            cipher().use { c ->
                val name = c.groupName(group)
                val sealed = c.seal(name, byteArrayOf(1))
                assertCode(MlsErrorCode.HistoryInvalid) { c.open(name, ByteArray(0)) }
                assertCode(MlsErrorCode.HistoryInvalid) { c.open(name, sealed.copyOf(sealed.size - 1)) }
                assertCode(MlsErrorCode.HistoryInvalid) { c.open(name, sealed.copyOf().also { it[0] = 0 }) }
                assertCode(MlsErrorCode.HistoryInvalid) { c.seal(name, ByteArray(MAX_PLAINTEXT + 1)) }
                assertCode(MlsErrorCode.HistoryInvalid) { c.seal(ByteArray(31), byteArrayOf(1)) }
                assertEquals(MAX_PLAINTEXT, c.open(name, c.seal(name, ByteArray(MAX_PLAINTEXT))).size)
            }
        }

    @Test
    fun badKeysAndOriginsAreRefusedAndTheKeyIsZeroed() =
        runTest {
            val factory = platformHistoryCipherFactory()
            assertCode(MlsErrorCode.InvalidInput) { factory.create(ByteArray(16), origin) }
            assertCode(MlsErrorCode.InvalidInput) { factory.create(key(), "") }
            val history = key()
            factory.create(history, origin).close()
            assertContentEquals(ByteArray(KEY_SIZE), history)
        }

    @Test
    fun aClosedCipherRefusesEveryCall() =
        runTest {
            val c = cipher()
            c.close()
            assertCode(MlsErrorCode.Poisoned) { c.groupName(group) }
        }

    private companion object {
        const val KEY_SIZE = 32
        const val NAME_SIZE = 32
        const val MAX_PLAINTEXT = 4092
    }
}
