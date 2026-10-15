package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import uniffi.gifitup_crypto.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The uniffi binding's error translation (#66 CR-2). */
class TranslatedTest {
    private fun failureOf(block: () -> Unit): MlsException = assertFailsWith<MlsException> { translated(block) }

    @Test
    fun nativeCodesMapByType() {
        assertEquals(MlsErrorCode.SnapshotAuth, failureOf { throw ErrorCode.SnapshotAuth() }.code)
        assertEquals(MlsErrorCode.Poisoned, failureOf { throw ErrorCode.Poisoned() }.code)
    }

    @Test
    fun anythingElseBecomesPoisonedWithoutDetail() {
        val e = failureOf { throw IllegalStateException("payload with secrets") }
        assertEquals(MlsErrorCode.Poisoned, e.code)
        assertNull(e.message)
        assertNull(e.cause)
    }
}
