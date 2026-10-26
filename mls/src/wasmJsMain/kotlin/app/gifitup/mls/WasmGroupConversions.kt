package app.gifitup.mls

import app.gifitup.shared.data.crypto.EncryptedMessage
import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.LeafInfo
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PreparedCommit
import app.gifitup.shared.data.crypto.WelcomeInfo

// Conversions of the wasm-bindgen group objects (crypto/src/wasm_api.rs) to the shared types. Every wasm-side object
// is freed, also when a conversion fails (#66 CR-5). Getters that return objects hand out new ones, freed here too.

private const val WORD = 4

/** `u32-BE length ‖ bytes` for each item (crypto/src/list.rs). */
internal fun List<ByteArray>.toLengthPrefixed(): ByteArray {
    val out = ByteArray(sumOf { WORD + it.size })
    var at = 0
    for (item in this) {
        at = out.putInt(at, item.size)
        item.copyInto(out, at)
        at += item.size
    }
    return out
}

/** `u32-BE` for each index (crypto/src/list.rs); a negative index is refused here, as on the JVM. */
internal fun List<Int>.toBigEndian(): ByteArray {
    if (any { it < 0 }) throw MlsException(MlsErrorCode.InvalidInput)
    val out = ByteArray(WORD * size)
    forEachIndexed { i, index -> out.putInt(WORD * i, index) }
    return out
}

/** Writes [value] big-endian at [at]; returns the position after it. */
private fun ByteArray.putInt(
    at: Int,
    value: Int,
): Int {
    for (i in 0 until WORD) this[at + i] = (value ushr (Byte.SIZE_BITS * (WORD - 1 - i))).toByte()
    return at + WORD
}

/**
 * Reads one leaf without freeing it; whoever got the object frees it exactly once. The index is a `u32` (a leaf that
 * is not in the tree yet is `u32::MAX`): going through `Long` wraps it to -1 like the JVM, while `Double.toInt()`
 * would saturate at `Int.MAX_VALUE`.
 */
private fun JsLeafInfo.toShared(): LeafInfo =
    LeafInfo(
        guarded { username.toJsString() }.toString(),
        guarded { signatureKey }.toByteArray(),
        guarded { leafIndex.toJsNumber() }.toDouble().toLong().toInt(),
    )

/** Converts and frees one leaf. */
internal fun JsLeafInfo.toSharedAndFree(): LeafInfo {
    try {
        return toShared()
    } finally {
        freeGuarded { free() }
    }
}

/** Converts a list of leaves; every wasm-side leaf is freed exactly once, also when a conversion fails. */
internal fun JsArray<JsLeafInfo>.toSharedAndFree(): List<LeafInfo> {
    val items = List(length) { i -> checkNotNull(this[i]) }
    try {
        return items.map { it.toShared() }
    } finally {
        items.forEach { freeGuarded { it.free() } }
    }
}

internal fun JsPreparedCommit.toSharedAndFree(): PreparedCommit {
    try {
        val welcome = guarded { welcome }.toByteArray()
        return PreparedCommit(
            guarded { commit }.toByteArray(),
            welcome.takeIf { it.isNotEmpty() },
            guarded { epoch.toJsNumber() }.toDouble().toLong(),
            guarded { added }.toSharedAndFree(),
            guarded { removed }.toSharedAndFree(),
        )
    } finally {
        freeGuarded { free() }
    }
}

internal fun JsEncryptedMessage.toSharedAndFree(): EncryptedMessage {
    try {
        return EncryptedMessage(guarded { message }.toByteArray(), guarded { messageRef }.toByteArray())
    } finally {
        freeGuarded { free() }
    }
}

internal fun JsIncomingMessage.toSharedAndFree(): IncomingMessage {
    try {
        val sender = guarded { sender }.toSharedAndFree()
        return if (guarded { isCommit.toJsBoolean() }.toBoolean()) {
            IncomingMessage.Commit(
                guarded { token.toJsNumber() }.toInt(),
                sender,
                guarded { epoch.toJsNumber() }.toDouble().toLong(),
                guarded { added }.toSharedAndFree(),
                guarded { removed }.toSharedAndFree(),
                guarded { selfRemoved.toJsBoolean() }.toBoolean(),
            )
        } else {
            IncomingMessage.Application(guarded { plaintext }.toByteArray(), sender, guarded { messageRef }.toByteArray())
        }
    } finally {
        freeGuarded { free() }
    }
}

internal fun JsWelcomeInfo.toSharedAndFree(): WelcomeInfo {
    try {
        return WelcomeInfo(
            guarded { token.toJsNumber() }.toInt(),
            guarded { groupId }.toByteArray(),
            guarded { sender }.toSharedAndFree(),
            guarded { members }.toSharedAndFree(),
        )
    } finally {
        freeGuarded { free() }
    }
}
