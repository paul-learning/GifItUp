@file:JsModule("gifitup-crypto")
// detekt flags constructor parameters of external classes as unused; they are the JS constructor signature.
@file:Suppress("UnusedPrivateProperty")

package app.gifitup.mls

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// Hand-written declarations for the wasm-bindgen output (gifitup_crypto.d.ts of crypto/src/wasm_api.rs),
// reviewed together with the Rust API. Only the v1 surface; the SPIKE group exports are not declared.

/** wasm-bindgen's default export: fetches and instantiates the wasm module. Call once. */
@JsName("default")
internal external fun initCrypto(): Promise<JsAny?>

internal external class NativeMlsClient(
    username: String,
    snapshotKey: Uint8Array,
) : JsAny {
    fun publicIdentity(): JsPublicIdentity

    fun createKeyPackages(count: Int): JsArray<JsCreatedKeyPackage>

    fun pruneExpiredKeyPackages(): Int

    fun inspectKeyPackage(
        keyPackage: Uint8Array,
        expectedUsername: String,
    ): JsPublicIdentity

    fun exportState(): Uint8Array

    /** [purpose] is the wire byte: 1 register, 2 login (crypto/src/auth.rs). */
    fun signAuth(
        purpose: Int,
        challenge: Uint8Array,
        origin: String,
        subject: String,
    ): Uint8Array

    /** Releases the Rust-side object (wasm memory is not garbage collected). */
    fun free()

    companion object {
        fun restore(
            state: Uint8Array,
            snapshotKey: Uint8Array,
        ): NativeMlsClient
    }
}

@JsName("PublicIdentity")
internal external class JsPublicIdentity : JsAny {
    val username: String
    val signaturePublicKey: Uint8Array

    fun free()
}

@JsName("CreatedKeyPackage")
internal external class JsCreatedKeyPackage : JsAny {
    val keyPackage: Uint8Array
    val keyPackageRef: Uint8Array
    val notAfter: Double

    fun free()
}
