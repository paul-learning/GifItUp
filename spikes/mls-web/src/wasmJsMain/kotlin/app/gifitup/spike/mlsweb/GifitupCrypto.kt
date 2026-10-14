@file:JsModule("gifitup-crypto")
// detekt flags constructor parameters of external classes as unused; they are the JS constructor signature.
@file:Suppress("UnusedPrivateProperty")

package app.gifitup.spike.mlsweb

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// SPIKE (#4): hand-written Kotlin/Wasm declarations for the wasm-bindgen output (gifitup_crypto.d.ts).

/** wasm-bindgen's default export: fetches and instantiates `gifitup_crypto_bg.wasm`. Call once. */
@JsName("default")
external fun initCrypto(): Promise<JsAny?>

external class MlsClient(
    username: String,
) : JsAny {
    fun createKeyPackage(): Uint8Array

    fun createGroup(): Uint8Array

    fun addMember(
        groupId: Uint8Array,
        keyPackage: Uint8Array,
    ): AddMemberResult

    fun joinFromWelcome(welcome: Uint8Array): Uint8Array

    fun encrypt(
        groupId: Uint8Array,
        plaintext: Uint8Array,
    ): Uint8Array

    /** Plaintext for application messages, `undefined` (null) for handshake messages. */
    fun process(
        groupId: Uint8Array,
        message: Uint8Array,
    ): Uint8Array?

    fun memberCount(groupId: Uint8Array): Int

    fun exportState(): Uint8Array

    /** Releases the Rust-side object (wasm memory is not garbage collected). */
    fun free()

    companion object {
        fun restore(state: Uint8Array): MlsClient
    }
}

external class AddMemberResult : JsAny {
    val commit: Uint8Array
    val welcome: Uint8Array

    fun free()
}
