package app.gifitup.shared.data.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

/**
 * The JSON configuration for writing content (ADR-0006 B2): the same flags as `ProtocolJson`, set explicitly so a change
 * shows up in review. Reading goes through [StrictJson] instead, because kotlinx can't reject duplicate keys.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val ContentJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        explicitNulls = true
        coerceInputValues = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
        allowComments = false
        decodeEnumsCaseInsensitive = false
        useAlternativeNames = false
        allowStructuredMapKeys = false
        useArrayPolymorphism = false
        classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
        namingStrategy = null
        prettyPrint = false
        exceptionsWithDebugInfo = false
    }
