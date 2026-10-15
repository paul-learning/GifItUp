package app.gifitup.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

/**
 * Decode-only serializer for the `Unknown` subtype of a sealed wire type (ADR-0006). Register it with
 * `polymorphicDefaultDeserializer` so that a subtype added by a newer peer decodes to [unknown] instead of failing the
 * whole payload. The input is consumed and dropped: [unknown] keeps no fields, so nothing a sender wrote can be shown
 * later. Encoding throws.
 */
class UnknownSubtypeSerializer<T : Any>(
    serialName: String,
    private val unknown: T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ): Unit = throw SerializationException("$descriptor can't be encoded")

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("$descriptor only supports JSON")
        json.decodeJsonElement()
        return unknown
    }
}
