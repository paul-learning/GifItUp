package app.gifitup.shared.data.network

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.JsAny

/**
 * A JavaScript `Uint8Array`, without a dependency on the browser bindings. Bytes cross in one piece as base64 text (a
 * per-byte call into JavaScript would take seconds for 8 MiB).
 */
internal external interface JsBytes : JsAny

@OptIn(ExperimentalEncodingApi::class)
internal fun JsBytes.toByteArray(): ByteArray = Base64.decode(jsToBase64(this))

@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toJsBytes(): JsBytes = jsFromBase64(Base64.encode(this))

@JsFun(
    """
(bytes) => {
  let text = '';
  for (let at = 0; at < bytes.length; at += 0x8000) {
    text += String.fromCharCode.apply(null, bytes.subarray(at, at + 0x8000));
  }
  return btoa(text);
}
""",
)
private external fun jsToBase64(bytes: JsBytes): String

@JsFun(
    """
(text) => {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let at = 0; at < binary.length; at++) bytes[at] = binary.charCodeAt(at);
  return bytes;
}
""",
)
private external fun jsFromBase64(text: String): JsBytes
