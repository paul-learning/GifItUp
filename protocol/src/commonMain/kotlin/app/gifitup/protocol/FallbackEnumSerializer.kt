package app.gifitup.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializer for wire enums that may gain values later (ADR-0006). A value this reader doesn't know decodes to
 * [fallback] instead of failing the whole payload. Receivers treat [fallback] as "not supported" (exhaustive `when`,
 * no `else`); the server rejects it in requests.
 *
 * The wire value is the constant's [Enum.name], matched case-sensitively, so renaming a constant is a breaking change.
 * Encoding [fallback] throws: it only ever stands for "something this build doesn't know".
 */
open class FallbackEnumSerializer<T : Enum<T>>(
    serialName: String,
    values: Iterable<T>,
    private val fallback: T,
) : KSerializer<T> {
    private val byName: Map<String, T> = values.filter { it != fallback }.associateBy { it.name }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        if (value == fallback) throw SerializationException("The fallback value of $descriptor can't be encoded")
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): T = byName[decoder.decodeString()] ?: fallback
}
