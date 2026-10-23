package app.gifitup.cli

import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.keypackages.CheckKeyPackages
import app.gifitup.shared.domain.keypackages.ClaimKeyPackages
import app.gifitup.shared.domain.keypackages.ClaimedDeviceKey
import app.gifitup.shared.domain.keypackages.RefillKeyPackages
import java.io.PrintStream

/**
 * The KeyPackage commands (ADR-0012), thin over the app's use cases: they print a fixed line per outcome and never
 * KeyPackage bytes, keys or signature keys. A claim consumes what it returns and is never repeated here.
 */
internal class KeyPackageCommands(
    private val api: GifItUpApi,
    private val refiller: RefillKeyPackages,
    private val claimer: ClaimKeyPackages,
    private val out: PrintStream,
    private val report: suspend (AuthResult.Failed) -> Int,
) {
    suspend fun refill(session: Session): Int =
        when (val result = refiller(session)) {
            is AuthResult.Ok -> done { out.println("uploaded ${result.value.uploaded}, ${result.value.available} available on the server") }
            is AuthResult.Failed -> report(result)
        }

    suspend fun status(session: Session): Int =
        when (val result = CheckKeyPackages(api)(session)) {
            is AuthResult.Ok -> {
                done {
                    out.println(
                        "${result.value.available} available on the server (low-water mark ${result.value.lowWaterMark})",
                    )
                }
            }

            is AuthResult.Failed -> {
                report(result)
            }
        }

    suspend fun claim(
        session: Session,
        target: String,
    ): Int =
        when (val result = claimer(session, target)) {
            is AuthResult.Ok -> done { result.value.forEach { out.println("${it.deviceId}  ${describe(it)}") } }
            is AuthResult.Failed -> report(result)
        }

    private fun describe(device: ClaimedDeviceKey): String =
        when (device) {
            is ClaimedDeviceKey.Usable -> "crate-valid, key pinned (credential ${device.identity.username})"
            is ClaimedDeviceKey.Empty -> "empty (no KeyPackage left)"
            is ClaimedDeviceKey.Unusable -> "unusable (the crate refused the server's KeyPackage)"
        }

    private inline fun done(print: () -> Unit): Int {
        print()
        return EXIT_OK
    }
}
