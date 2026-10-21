package app.gifitup.shared.domain.keypackages

import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.KeyPackageStatus
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.toAuth

/** How many KeyPackages this device has on the server, and the server's low-water mark (untrusted; see [RefillKeyPackages]). */
class CheckKeyPackages(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(session: Session): AuthResult<KeyPackageStatus> = api.keyPackageStatus(session).toAuth()
}
