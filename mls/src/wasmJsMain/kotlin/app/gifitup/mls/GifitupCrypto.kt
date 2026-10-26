@file:JsModule("gifitup-crypto")
// detekt flags constructor parameters of external classes as unused; they are the JS constructor signature.
@file:Suppress("UnusedPrivateProperty")

package app.gifitup.mls

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// Hand-written declarations for the wasm-bindgen output (gifitup_crypto.d.ts of crypto/src/wasm_api.rs),
// reviewed together with the Rust API. Lists cross as one Uint8Array (crypto/src/list.rs); epochs and counts are
// JS numbers (exact below 2^53).

/** wasm-bindgen's default export: fetches and instantiates the wasm module. Call once. */
@JsName("default")
internal external fun initCrypto(): Promise<JsAny?>

@Suppress("TooManyFunctions") // Mirrors the wasm-bindgen class one to one (crypto/src/wasm_api.rs).
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

    /** 12 indices into the app's emoji table for a 32-byte signature public key (crypto/src/fingerprint.rs). */
    fun fingerprint(signatureKey: Uint8Array): Uint8Array

    fun exportState(): Uint8Array

    fun rekeyAndExport(newKey: Uint8Array): Uint8Array

    fun exportsUnderKey(): Double

    fun setAppState(state: Uint8Array)

    fun appState(): Uint8Array

    fun createGroup(): Uint8Array

    /** [keyPackages]: each as u32-BE length then bytes. */
    fun prepareAdd(
        groupId: Uint8Array,
        keyPackages: Uint8Array,
    ): JsPreparedCommit

    /** [leafIndexes]: each as u32-BE. */
    fun prepareRemove(
        groupId: Uint8Array,
        leafIndexes: Uint8Array,
    ): JsPreparedCommit

    fun prepareUpdate(groupId: Uint8Array): JsPreparedCommit

    fun mergePending(groupId: Uint8Array): Double

    fun clearPending(groupId: Uint8Array)

    fun hasPendingCommit(groupId: Uint8Array): Boolean

    fun encrypt(
        groupId: Uint8Array,
        plaintext: Uint8Array,
    ): JsEncryptedMessage

    fun stageIncoming(
        groupId: Uint8Array,
        message: Uint8Array,
    ): JsIncomingMessage

    fun mergeStaged(token: Int): Double

    fun discardStaged(token: Int)

    fun inspectWelcome(
        welcome: Uint8Array,
        expectedGroupId: Uint8Array,
    ): JsWelcomeInfo

    fun joinWelcome(token: Int): Uint8Array

    fun discardWelcome(token: Int)

    fun forgetGroup(groupId: Uint8Array)

    fun members(groupId: Uint8Array): JsArray<JsLeafInfo>

    fun memberCount(groupId: Uint8Array): Int

    fun epoch(groupId: Uint8Array): Double

    /** [purpose] is exactly "register" or "login" (`AuthPurpose::from_name` in crypto/src/auth.rs). */
    fun signAuth(
        purpose: String,
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

@JsName("LeafInfo")
internal external class JsLeafInfo : JsAny {
    val username: String
    val signatureKey: Uint8Array

    /** A `u32` as a JS number; `Double` so no Int conversion happens before [toShared] wraps it. */
    val leafIndex: Double

    fun free()
}

@JsName("PreparedCommit")
internal external class JsPreparedCommit : JsAny {
    val commit: Uint8Array

    /** Empty when the commit adds nobody. */
    val welcome: Uint8Array
    val epoch: Double
    val added: JsArray<JsLeafInfo>
    val removed: JsArray<JsLeafInfo>

    fun free()
}

@JsName("EncryptedMessage")
internal external class JsEncryptedMessage : JsAny {
    val message: Uint8Array
    val messageRef: Uint8Array

    fun free()
}

@JsName("IncomingMessage")
internal external class JsIncomingMessage : JsAny {
    val isCommit: Boolean
    val token: Int
    val plaintext: Uint8Array
    val sender: JsLeafInfo
    val messageRef: Uint8Array
    val epoch: Double
    val added: JsArray<JsLeafInfo>
    val removed: JsArray<JsLeafInfo>
    val selfRemoved: Boolean

    fun free()
}

@JsName("WelcomeInfo")
internal external class JsWelcomeInfo : JsAny {
    val token: Int
    val groupId: Uint8Array
    val sender: JsLeafInfo
    val members: JsArray<JsLeafInfo>

    fun free()
}
