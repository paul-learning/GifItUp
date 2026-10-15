package app.gifitup.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** One test per compatibility rule in ADR-0006, run against [ProtocolJson] with test-only fixture models. */
class CompatibilityRulesTest {
    @Test
    fun unknownKeysAreIgnored() {
        val decoded = ProtocolJson.decodeFromString<Fixture>("""{"name":"a","count":1,"addedLater":true}""")

        assertEquals(Fixture(name = "a", count = 1), decoded)
    }

    @Test
    fun defaultsAreEncoded() {
        assertEquals("""{"name":"a","count":0}""", ProtocolJson.encodeToString(Fixture(name = "a")))
    }

    @Test
    fun addedFieldMissingFromOlderWriterTakesItsDefault() {
        assertEquals(Fixture(name = "a", count = 0), ProtocolJson.decodeFromString<Fixture>("""{"name":"a"}"""))
    }

    @Test
    fun unknownEnumValueDecodesToUnknown() {
        assertEquals(Kind.UNKNOWN, decodeKind("ADDED_LATER"))
    }

    @Test
    fun enumValuesAreCaseSensitive() {
        assertEquals(Kind.ALPHA, decodeKind("ALPHA"))
        assertEquals(Kind.UNKNOWN, decodeKind("alpha"))
    }

    @Test
    fun encodingUnknownEnumValueFails() {
        assertFailsWith<SerializationException> { ProtocolJson.encodeToString(WithKind(Kind.UNKNOWN)) }
    }

    @Test
    fun unknownSubtypeDecodesToUnknownWithoutKeepingFields() {
        val decoded = sealedJson.decodeFromString<Event>("""{"type":"added_later","text":"from a newer peer"}""")

        assertEquals(Event.Unknown, decoded)
    }

    @Test
    fun knownSubtypeUsesTypeDiscriminatorAndSerialName() {
        val json = sealedJson.encodeToString<Event>(Event.Joined(member = "m"))

        assertEquals("""{"type":"joined","member":"m"}""", json)
        assertEquals(Event.Joined(member = "m"), sealedJson.decodeFromString<Event>(json))
    }

    @Test
    fun encodingUnknownSubtypeFails() {
        assertFailsWith<SerializationException> { sealedJson.encodeToString<Event>(Event.Unknown) }
    }

    @Test
    fun nullForNonNullFieldFails() {
        assertDecodeFails("""{"name":null}""")
    }

    @Test
    fun unquotedStringFails() {
        assertDecodeFails("""{"name":a}""")
    }

    @Test
    fun trailingCommaFails() {
        assertDecodeFails("""{"name":"a",}""")
    }

    @Test
    fun commentsFail() {
        assertDecodeFails("""{"name":"a" /* c */}""")
    }

    @Test
    fun nanFails() {
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<WithDouble>("""{"value":NaN}""") }
    }

    private fun decodeKind(value: String): Kind = ProtocolJson.decodeFromString<WithKind>("""{"kind":"$value"}""").kind

    private fun assertDecodeFails(json: String) {
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<Fixture>(json) }
    }

    @Serializable
    private data class Fixture(
        val name: String,
        val count: Int = 0,
    )

    @Serializable
    private data class WithDouble(
        val value: Double,
    )

    @Serializable(with = KindSerializer::class)
    private enum class Kind { ALPHA, BETA, UNKNOWN }

    private object KindSerializer : FallbackEnumSerializer<Kind>("Kind", Kind.entries, Kind.UNKNOWN)

    @Serializable
    private data class WithKind(
        val kind: Kind,
    )

    @Serializable
    private sealed interface Event {
        @Serializable
        @SerialName("joined")
        data class Joined(
            val member: String,
        ) : Event

        @Serializable(with = UnknownEventSerializer::class)
        data object Unknown : Event
    }

    private object UnknownEventSerializer : KSerializer<Event.Unknown> by UnknownSubtypeSerializer(
        "Event.Unknown",
        Event.Unknown,
    )

    private val sealedJson =
        Json(ProtocolJson) {
            serializersModule =
                SerializersModule {
                    polymorphic(Event::class) { defaultDeserializer { UnknownEventSerializer } }
                }
        }
}
