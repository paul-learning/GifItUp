package app.gifitup.shared.data.network

import kotlin.js.JsAny

/** A JavaScript `Uint8Array`, without a dependency on the browser bindings: just what the web media code needs. */
internal external interface JsBytes : JsAny

@JsFun("(bytes) => bytes.length")
internal external fun jsLength(bytes: JsBytes): Int

@JsFun("(bytes, index) => bytes[index]")
internal external fun jsByteAt(
    bytes: JsBytes,
    index: Int,
): Int

@JsFun("(size) => new Uint8Array(size)")
internal external fun newJsBytes(size: Int): JsBytes

@JsFun("(bytes, index, value) => { bytes[index] = value; }")
internal external fun jsSetByte(
    bytes: JsBytes,
    index: Int,
    value: Int,
)
