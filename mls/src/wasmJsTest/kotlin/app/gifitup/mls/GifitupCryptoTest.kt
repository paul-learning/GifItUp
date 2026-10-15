@file:JsModule("gifitup-crypto-test")
// detekt flags constructor parameters of external classes as unused; they are the JS constructor signature.
@file:Suppress("UnusedPrivateProperty")

package app.gifitup.mls

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// The test build of the crate (debug, `test-panic` feature, own npm name; ADR-0007). Only what the trap test
// needs: `inspectKeyPackage` panics on the reserved username "__panic__", which on wasm32 is a trap.

@JsName("default")
internal external fun initTestCrypto(): Promise<JsAny?>

@JsName("NativeMlsClient")
internal external class TestNativeMlsClient(
    username: String,
    snapshotKey: Uint8Array,
) : JsAny {
    fun inspectKeyPackage(
        keyPackage: Uint8Array,
        expectedUsername: String,
    ): JsAny

    fun exportState(): Uint8Array
}
