package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * A message reference as content carries it (ADR-0006 B9): unpadded base64url of 32 bytes, 43 characters, canonical
 * (the unused trailing bits are zero). It is parsed from text a member wrote, so anything else is `null`.
 */
object MessageRefs {
    const val SIZE = 32
    private val PATTERN = Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

    fun parse(text: String): KeyBytes? = if (PATTERN.matches(text)) Base64Url.decode(text, SIZE..SIZE)?.let(::KeyBytes) else null

    fun format(reference: KeyBytes): String = Base64Url.encode(reference.bytes)
}
