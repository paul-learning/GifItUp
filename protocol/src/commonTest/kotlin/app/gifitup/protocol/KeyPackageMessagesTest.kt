package app.gifitup.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Wire format of the KeyPackage directory messages (ADR-0012) under the ADR-0006 rules. */
class KeyPackageMessagesTest {
    @Test
    fun uploadRequestAndResponseHaveStableFieldNames() {
        val request = UploadKeyPackagesRequest(listOf("AAEC", "AwQF"))
        val requestJson = """{"keyPackages":["AAEC","AwQF"]}"""
        assertEquals(requestJson, ProtocolJson.encodeToString(request))
        assertEquals(request, ProtocolJson.decodeFromString<UploadKeyPackagesRequest>(requestJson))

        val response = UploadKeyPackagesResponse(accepted = 2, available = 42, lowWaterMark = 20)
        val responseJson = """{"accepted":2,"available":42,"lowWaterMark":20}"""
        assertEquals(responseJson, ProtocolJson.encodeToString(response))
        assertEquals(response, ProtocolJson.decodeFromString<UploadKeyPackagesResponse>(responseJson))
    }

    @Test
    fun statusHasStableFieldNames() {
        val status = KeyPackageStatusResponse(available = 7, lowWaterMark = 20)
        val json = """{"available":7,"lowWaterMark":20}"""
        assertEquals(json, ProtocolJson.encodeToString(status))
        assertEquals(status, ProtocolJson.decodeFromString<KeyPackageStatusResponse>(json))
    }

    @Test
    fun claimRequestCarriesAnOrderedSuitePreference() {
        val request = ClaimKeyPackagesRequest("ann", listOf(1, 3))
        val json = """{"username":"ann","ciphersuites":[1,3]}"""
        assertEquals(json, ProtocolJson.encodeToString(request))
        assertEquals(request, ProtocolJson.decodeFromString<ClaimKeyPackagesRequest>(json))
    }

    @Test
    fun claimResponseKeepsAnExplicitNullForADeviceWithoutKeyPackages() {
        val response =
            ClaimKeyPackagesResponse(
                listOf(
                    ClaimedDevice("123e4567-e89b-12d3-a456-426614174000", "AAEC"),
                    ClaimedDevice("123e4567-e89b-12d3-a456-426614174001", null),
                ),
            )
        val json =
            """{"devices":[{"deviceId":"123e4567-e89b-12d3-a456-426614174000","keyPackage":"AAEC"},""" +
                """{"deviceId":"123e4567-e89b-12d3-a456-426614174001","keyPackage":null}]}"""
        assertEquals(json, ProtocolJson.encodeToString(response))
        assertEquals(response, ProtocolJson.decodeFromString<ClaimKeyPackagesResponse>(json))
    }

    /** The response has no suite and no remaining count: a client never learns either from the server (ADR-0012). */
    @Test
    fun claimResponseHasNoSuiteAndNoCount() {
        val json = ProtocolJson.encodeToString(ClaimKeyPackagesResponse(listOf(ClaimedDevice("d", "AAEC"))))
        val top = ProtocolJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("devices"), top.keys)
        assertEquals(
            setOf("deviceId", "keyPackage"),
            top
                .getValue("devices")
                .jsonArray
                .single()
                .jsonObject.keys,
        )
    }

    @Test
    fun everyFieldIsRequired() {
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<UploadKeyPackagesRequest>("{}") }
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString<UploadKeyPackagesResponse>(
                """{"accepted":1,"available":1}""",
            )
        }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<KeyPackageStatusResponse>("""{"available":1}""") }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<ClaimKeyPackagesRequest>("""{"username":"ann"}""") }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<ClaimedDevice>("""{"deviceId":"d"}""") }
    }

    @Test
    fun unknownFieldsAreIgnoredSoTheMessagesCanGrow() {
        val status = ProtocolJson.decodeFromString<KeyPackageStatusResponse>("""{"available":1,"lowWaterMark":2,"later":true}""")
        assertEquals(KeyPackageStatusResponse(1, 2), status)
    }

    @Test
    fun theNewErrorCodesHaveTheirWireNames() {
        assertEquals("""{"error":"key_package_invalid"}""", ProtocolJson.encodeToString(ErrorResponse(ApiErrorCode.KEY_PACKAGE_INVALID)))
        assertEquals("""{"error":"key_package_limit"}""", ProtocolJson.encodeToString(ErrorResponse(ApiErrorCode.KEY_PACKAGE_LIMIT)))
        assertEquals(
            ApiErrorCode.KEY_PACKAGE_LIMIT,
            ProtocolJson.decodeFromString<ErrorResponse>("""{"error":"key_package_limit"}""").error,
        )
    }
}
