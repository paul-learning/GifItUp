package app.gifitup.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for all wire models. Clients and server must both use it.
 *
 * Every flag is set explicitly, including those left at the library default, so that a change shows up in review.
 * The compatibility rules behind them are in ADR-0006 and `CompatibilityRulesTest`:
 * - `ignoreUnknownKeys`: fields added by a newer peer never break an older reader.
 * - `encodeDefaults`: the writer sends every field, so a reader never fills in its own (possibly different) default.
 * - `exceptionsWithDebugInfo` is off, so lexer errors carry no input excerpt. Other messages still name unknown enum
 *   values and type names: never log, show or return decoder exception messages (ADR-0006 A8).
 * - `useAlternativeNames` is off: a field has exactly one wire name.
 * - everything else stays strict: no lenient strings, no `null` coerced into defaults, no case-insensitive enums,
 *   no comments, trailing commas or `NaN`.
 */
@OptIn(ExperimentalSerializationApi::class)
val ProtocolJson: Json =
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
