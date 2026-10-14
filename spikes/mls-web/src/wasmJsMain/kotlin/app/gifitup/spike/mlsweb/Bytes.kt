package app.gifitup.spike.mlsweb

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

// SPIKE (#4): Kotlin/Wasm ByteArray lives in the Wasm GC heap and JS cannot see it, so every
// crossing to the wasm-bindgen module copies element by element. Measured in MlsWebRoundTripTest.

fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }
