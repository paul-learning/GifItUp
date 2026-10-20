package app.gifitup.server.keypackages

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ClaimKeyPackagesRequest
import app.gifitup.protocol.ClaimKeyPackagesResponse
import app.gifitup.protocol.ClaimedDevice
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.KeyPackageStatusResponse
import app.gifitup.protocol.UploadKeyPackagesRequest
import app.gifitup.protocol.UploadKeyPackagesResponse
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.auth.UsernamePolicy
import app.gifitup.server.auth.session
import app.gifitup.server.db.KeyPackageRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val KEY_PACKAGES_PATH = "/key-packages"
private const val STATUS_PATH = "$KEY_PACKAGES_PATH/status"
private const val CLAIM_PATH = "$KEY_PACKAGES_PATH/claim"
private const val MAX_SUITES = KeyPackageRepository.MAX_SUITES
private const val MAX_SUITE_ID = 65_535

/**
 * 50 KeyPackages of 2,048 bytes in base64url (2,731 characters each) plus JSON framing, with margin. Only `POST
 * /key-packages` gets it; every other path keeps the small default (ADR-0012).
 */
internal const val UPLOAD_BODY_LIMIT_BYTES = 160L * 1024

/** The body limit for [call]: the upload endpoint's, or [default]. Exact method and path, nothing else matches. */
internal fun keyPackageBodyLimit(
    call: ApplicationCall,
    default: Long,
): Long =
    if (call.request.httpMethod == HttpMethod.Post && call.request.path() == KEY_PACKAGES_PATH) {
        UPLOAD_BODY_LIMIT_BYTES
    } else {
        default
    }

/**
 * The KeyPackage endpoints (ADR-0012). Not public, so the session interceptor has already checked the caller; the
 * account and device are the session's, never the request's.
 */
internal fun Route.keyPackageRoutes(keyPackages: KeyPackageService) {
    uploadRoute(keyPackages)
    statusRoute(keyPackages)
    claimRoute(keyPackages)
}

private fun Route.uploadRoute(keyPackages: KeyPackageService) {
    post(KEY_PACKAGES_PATH) {
        val request = call.receive<UploadKeyPackagesRequest>()
        if (request.keyPackages.size !in 1..KeyPackageService.MAX_BATCH) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiErrorCode.BAD_REQUEST))
            return@post
        }
        val deviceId = call.session.record.deviceId
        when (val outcome = withContext(Dispatchers.IO) { keyPackages.upload(deviceId, request.keyPackages) }) {
            is UploadOutcome.Accepted -> {
                call.respond(UploadKeyPackagesResponse(outcome.accepted, outcome.available, KeyPackageService.LOW_WATER_MARK))
            }

            UploadOutcome.Invalid -> {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiErrorCode.KEY_PACKAGE_INVALID))
            }

            UploadOutcome.LimitReached -> {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiErrorCode.KEY_PACKAGE_LIMIT))
            }

            UploadOutcome.RateLimited -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ApiErrorCode.RATE_LIMITED))
            }

            UploadOutcome.NoDevice -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiErrorCode.UNAUTHORIZED))
            }
        }
    }
}

private fun Route.statusRoute(keyPackages: KeyPackageService) {
    get(STATUS_PATH) {
        val available = withContext(Dispatchers.IO) { keyPackages.available(call.session.record.deviceId) }
        if (available == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiErrorCode.UNAUTHORIZED))
        } else {
            call.noStore()
            call.respond(KeyPackageStatusResponse(available, KeyPackageService.LOW_WATER_MARK))
        }
    }
}

private fun Route.claimRoute(keyPackages: KeyPackageService) {
    post(CLAIM_PATH) {
        val request = call.receive<ClaimKeyPackagesRequest>()
        // Validated before any limit is touched: a malformed request spends nothing and learns nothing.
        if (!UsernamePolicy.isValid(request.username) || !validSuites(request.ciphersuites)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiErrorCode.BAD_REQUEST))
            return@post
        }
        val claimer = call.session.record.accountId
        when (val outcome = withContext(Dispatchers.IO) { keyPackages.claim(claimer, request.username, request.ciphersuites) }) {
            is ClaimOutcome.Claimed -> {
                call.noStore() // the response carries KeyPackages
                call.respond(
                    ClaimKeyPackagesResponse(
                        outcome.devices.map { ClaimedDevice(it.deviceId.toString(), it.keyPackage?.let(Base64Url::encode)) },
                    ),
                )
            }

            // Unknown username and the claimer's own: byte-identical (no probing).
            ClaimOutcome.NotFound -> {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiErrorCode.NOT_FOUND))
            }

            ClaimOutcome.RateLimited -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(ApiErrorCode.RATE_LIMITED))
            }
        }
    }
}

private fun validSuites(suites: List<Int>): Boolean =
    suites.size in 1..MAX_SUITES && suites.distinct().size == suites.size && suites.all { it in 1..MAX_SUITE_ID }

private fun ApplicationCall.noStore() = response.headers.append(HttpHeaders.CacheControl, "no-store")
