package app.gifitup.shared.data.device

import android.os.Build
import app.gifitup.shared.domain.media.DeviceSecurityPatch

/** The device's security patch level, as the platform reports it (`YYYY-MM-DD`); read once per call, nothing is stored. */
class AndroidSecurityPatch : DeviceSecurityPatch {
    override fun level(): String? = Build.VERSION.SECURITY_PATCH
}
