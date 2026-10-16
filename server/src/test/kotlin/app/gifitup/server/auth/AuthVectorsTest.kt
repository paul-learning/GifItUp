package app.gifitup.server.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The crate's committed vectors (crypto/src/auth.rs writes them; ADR-0009): the server must rebuild exactly the bytes
 * the device signed, and accept the signature only for those bytes.
 */
class AuthVectorsTest {
    @Serializable
    private data class Vectors(
        val label: String,
        val publicKey: String,
        val vectors: List<Vector>,
    )

    @Serializable
    private data class Vector(
        val purpose: String,
        val challenge: String,
        val origin: String,
        val subject: String,
        val signContent: String,
        val signature: String,
    )

    private val file: Vectors =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            checkNotNull(javaClass.getResource("/auth-signature-vectors.json")).readText(),
        )
    private val key = assertNotNull(SignatureVerifier.decodePublicKey(hex(file.publicKey)))

    private val Vector.purposeEnum get() = if (purpose == "register") AuthPurpose.REGISTER else AuthPurpose.LOGIN

    @Test
    fun labelMatchesTheCrate() {
        assertEquals(AUTH_SIGNATURE_LABEL, file.label)
    }

    @Test
    fun serverRebuildsTheExactSignedBytes() {
        assertEquals(3, file.vectors.size)
        file.vectors.forEach { v ->
            assertContentEquals(hex(v.signContent), authSignContent(v.purposeEnum, hex(v.challenge), v.origin, v.subject), v.subject)
        }
    }

    @Test
    fun vectorSignaturesVerify() {
        file.vectors.forEach { v ->
            val content = authSignContent(v.purposeEnum, hex(v.challenge), v.origin, v.subject)
            assertTrue(SignatureVerifier.verify(key, content, hex(v.signature)), v.subject)
        }
    }

    /** #68 round 0, SEC-14: changing any one field must break verification. */
    @Test
    fun everyFieldIsCovered() {
        val v = file.vectors.first { it.purpose == "login" }
        val signature = hex(v.signature)
        val challenge = hex(v.challenge)
        val flipped = challenge.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val variants =
            listOf(
                authSignContent(AuthPurpose.REGISTER, challenge, v.origin, v.subject),
                authSignContent(AuthPurpose.LOGIN, flipped, v.origin, v.subject),
                authSignContent(AuthPurpose.LOGIN, challenge, v.origin + "/", v.subject),
                authSignContent(AuthPurpose.LOGIN, challenge, v.origin.uppercase(), v.subject),
                authSignContent(AuthPurpose.LOGIN, challenge, v.origin + ":443", v.subject),
                authSignContent(AuthPurpose.LOGIN, challenge, v.origin, v.subject.uppercase()),
                authSignContent(AuthPurpose.LOGIN, challenge, v.origin, v.subject.replace('0', '1')),
            )
        variants.forEach { content -> assertFalse(SignatureVerifier.verify(key, content, signature)) }
    }

    @Test
    fun lengthPrefixesUseMinimalVarints() {
        // 63 bytes: one length byte; 64 bytes: two (0x40 0x40), as in the crate's third vector.
        val short = authSignContent(AuthPurpose.LOGIN, ByteArray(63), "https://a.b", "x")
        val long = authSignContent(AuthPurpose.LOGIN, ByteArray(64), "https://a.b", "x")
        // label<V> (1 + 23 bytes) ‖ content length (2 bytes: content is over 63) ‖ purpose ‖ challenge length ...
        val challengeLengthAt = 1 + AUTH_SIGNATURE_LABEL.length + 2 + 1
        assertEquals(63, short[challengeLengthAt].toInt())
        assertEquals(0x40, long[challengeLengthAt].toInt())
        assertEquals(0x40, long[challengeLengthAt + 1].toInt())
    }
}
