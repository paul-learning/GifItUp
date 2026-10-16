package app.gifitup.server.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
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

    private val Vector.typedSubject: AuthSubject
        get() = if (purpose == "register") checkNotNull(AuthSubject.Username.of(subject)) else AuthSubject.Device(UUID.fromString(subject))

    @Test
    fun labelMatchesTheCrate() {
        assertEquals(AUTH_SIGNATURE_LABEL, file.label)
    }

    @Test
    fun serverRebuildsTheExactSignedBytes() {
        assertEquals(3, file.vectors.size)
        file.vectors.forEach { v ->
            val origin = checkNotNull(CanonicalOrigin.of(v.origin))
            assertContentEquals(hex(v.signContent), authSignContent(hex(v.challenge), origin, v.typedSubject), v.subject)
        }
    }

    @Test
    fun vectorSignaturesVerify() {
        file.vectors.forEach { v ->
            val content = authSignContent(hex(v.challenge), checkNotNull(CanonicalOrigin.of(v.origin)), v.typedSubject)
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
                authSignContentUnchecked(AuthPurpose.REGISTER, challenge, v.origin, v.subject),
                authSignContentUnchecked(AuthPurpose.LOGIN, flipped, v.origin, v.subject),
                authSignContentUnchecked(AuthPurpose.LOGIN, challenge, v.origin + "/", v.subject),
                authSignContentUnchecked(AuthPurpose.LOGIN, challenge, v.origin.uppercase(), v.subject),
                authSignContentUnchecked(AuthPurpose.LOGIN, challenge, v.origin + ":443", v.subject),
                authSignContentUnchecked(AuthPurpose.LOGIN, challenge, v.origin, v.subject.uppercase()),
                authSignContentUnchecked(AuthPurpose.LOGIN, challenge, v.origin, v.subject.replace('0', '1')),
            )
        variants.forEach { content -> assertFalse(SignatureVerifier.verify(key, content, signature)) }
    }

    @Test
    fun deviceSubjectsAreAlwaysCanonical() {
        // UUID.fromString accepts uppercase; the signed text is always the canonical lowercase form (SEC-31).
        assertEquals(
            "123e4567-e89b-12d3-a456-426614174000",
            AuthSubject.Device(UUID.fromString("123E4567-E89B-12D3-A456-426614174000")).text,
        )
        assertEquals(null, AuthSubject.Username.of("Alice"))
    }

    @Test
    fun lengthPrefixesUseMinimalVarints() {
        // 63 bytes: one length byte; 64 bytes: two (0x40 0x40), as in the crate's third vector.
        val short = authSignContentUnchecked(AuthPurpose.LOGIN, ByteArray(63), "https://a.b", "x")
        val long = authSignContentUnchecked(AuthPurpose.LOGIN, ByteArray(64), "https://a.b", "x")
        // label<V> (1 + 23 bytes) ‖ content length (2 bytes: content is over 63) ‖ purpose ‖ challenge length ...
        val challengeLengthAt = 1 + AUTH_SIGNATURE_LABEL.length + 2 + 1
        assertEquals(63, short[challengeLengthAt].toInt())
        assertEquals(0x40, long[challengeLengthAt].toInt())
        assertEquals(0x40, long[challengeLengthAt + 1].toInt())
    }
}
