@file:Suppress("MagicNumber", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName")
// A test-side encoder of the wire layout: the numbers are the layout, and every rule has its own knob.

package app.gifitup.keypackage

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * An independent test-side encoder of a KeyPackage MLSMessage (RFC 9420 §10, §7.2), with a knob for every rule the
 * reader enforces. It signs with the JDK, so valid output is valid for the reader's own checks, and it shares no code
 * with the reader beyond the layout in the RFC.
 */
class KeyPackageSpec(
    var mlsVersion: Int = 1,
    var wireFormat: Int = 5,
    var keyPackageVersion: Int = 1,
    var suite: Int = 1,
    var initKey: ByteArray = ByteArray(32) { 1 },
    var encryptionKey: ByteArray = ByteArray(32) { 2 },
    var advertisedSignatureKey: ByteArray? = null,
    var credentialType: Int = 1,
    var identity: ByteArray = "ann".encodeToByteArray(),
    var source: Int = 1,
    var notBefore: Long = 1_000L,
    var notAfter: Long = 2_000L,
    var leafExtensions: List<Pair<Int, ByteArray>> = emptyList(),
    var keyPackageExtensions: List<Pair<Int, ByteArray>> = emptyList(),
    var fatVarints: Boolean = false,
    var fourByteVarints: Boolean = false,
    var leafSigner: KeyPair = SIGNER,
    var keyPackageSigner: KeyPair = SIGNER,
    var keyPackageSignatureBytes: Int = 64,
    var capabilityVersions: List<Int> = listOf(1),
    var capabilitySuites: List<Int> = listOf(1, 2, 3),
    var capabilityExtensions: List<Int> = emptyList(),
    var capabilityProposals: List<Int> = emptyList(),
    var capabilityCredentials: List<Int> = listOf(1),
) {
    val signatureKey: ByteArray get() = advertisedSignatureKey ?: rawPublicKey(SIGNER)

    companion object {
        val SIGNER: KeyPair = newKeyPair()
        val OTHER: KeyPair = newKeyPair()

        fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        /** The 32 raw bytes: the tail of the X.509 SubjectPublicKeyInfo. */
        fun rawPublicKey(pair: KeyPair): ByteArray =
            pair.public.encoded
                .takeLast(32)
                .toByteArray()
    }
}

fun buildKeyPackage(spec: KeyPackageSpec = KeyPackageSpec()): ByteArray {
    fun varint(n: Int): ByteArray =
        when {
            spec.fourByteVarints -> byteArrayOf((0x80 or (n shr 24)).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
            spec.fatVarints && n < 16384 -> byteArrayOf((0x40 or (n shr 8)).toByte(), n.toByte())
            n < 64 -> byteArrayOf(n.toByte())
            n < 16384 -> byteArrayOf((0x40 or (n shr 8)).toByte(), n.toByte())
            else -> error("too long for a test KeyPackage")
        }

    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun u64(v: Long) = ByteArray(8) { (v shr (56 - 8 * it)).toByte() }

    fun vec(b: ByteArray) = varint(b.size) + b

    fun extensions(list: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        list.forEach { (type, data) ->
            out.write(u16(type))
            out.write(vec(data))
        }
        return vec(out.toByteArray())
    }

    fun sign(
        label: String,
        content: ByteArray,
        signer: KeyPair,
        length: Int = 64,
    ): ByteArray {
        val fullLabel = ("MLS 1.0 $label").encodeToByteArray()
        val signed =
            Signature.getInstance("Ed25519").run {
                initSign(signer.private)
                update(vec(fullLabel) + vec(content))
                sign()
            }
        return signed.copyOf(length)
    }

    fun u16s(list: List<Int>) = vec(list.fold(ByteArray(0)) { acc, v -> acc + u16(v) })

    val capabilities =
        u16s(spec.capabilityVersions) + u16s(spec.capabilitySuites) + u16s(spec.capabilityExtensions) +
            u16s(spec.capabilityProposals) + u16s(spec.capabilityCredentials)
    val leafTbs =
        vec(spec.encryptionKey) + vec(spec.signatureKey) + u16(spec.credentialType) + vec(spec.identity) + capabilities +
            byteArrayOf(spec.source.toByte()) + u64(spec.notBefore) + u64(spec.notAfter) + extensions(spec.leafExtensions)
    val leaf = leafTbs + vec(sign("LeafNodeTBS", leafTbs, spec.leafSigner))
    val keyPackageTbs = u16(spec.keyPackageVersion) + u16(spec.suite) + vec(spec.initKey) + leaf + extensions(spec.keyPackageExtensions)
    val signature = sign("KeyPackageTBS", keyPackageTbs, spec.keyPackageSigner, spec.keyPackageSignatureBytes)
    return u16(spec.mlsVersion) + u16(spec.wireFormat) + keyPackageTbs + vec(signature)
}
