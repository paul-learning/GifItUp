@file:Suppress("MagicNumber") // The civil-calendar arithmetic is the content of this file.

package app.gifitup.shared.domain.diagnostics

/** What the report says about the app itself; every field is a fixed word or a number, never a user's value. */
data class DiagInfo(
    val version: String,
    val platform: String,
    /** `true` for a server other than the build's default; the report says "default" or "custom", never the host. */
    val customServer: Boolean,
    /** A bucket, not the number: see [bucket]. */
    val chats: String,
    val liveConnected: Boolean,
) {
    companion object {
        /** The coarse number of chats the report says: "0", "1-5" or "6+" (plan review SEC-3). */
        fun bucket(count: Int): String =
            when {
                count <= 0 -> "0"
                count <= 5 -> "1-5"
                else -> "6+"
            }
    }
}

/**
 * The text the user saves or shares. Built only from [DiagInfo] and the closed [DiagEvent] set, so it carries no
 * name, host, key or message content by construction (tested with every event).
 */
object DiagnosticsReport {
    fun render(
        info: DiagInfo,
        entries: List<DiagEntry>,
        nowSeconds: Long,
    ): String =
        buildString {
            appendLine("GifItUp log")
            appendLine("version: ${info.version}")
            appendLine("platform: ${info.platform}")
            appendLine("server: ${if (info.customServer) "custom" else "default"}")
            appendLine("chats: ${info.chats}")
            appendLine("live updates connected: ${yesNo(info.liveConnected)}")
            appendLine("created: ${utc(nowSeconds)}")
            appendLine()
            appendLine("events (oldest first, UTC):")
            if (entries.isEmpty()) appendLine("(none)")
            entries.forEach { appendLine("${utc(it.at)}  ${it.event.label}") }
        }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** `YYYY-MM-DD HH:MM:SS` for seconds since 1970 (UTC), without a platform date library. */
    internal fun utc(seconds: Long): String {
        val days = seconds.floorDiv(SECONDS_PER_DAY)
        val rest = seconds.mod(SECONDS_PER_DAY).toInt()
        val (y, m, d) = civil(days)
        return "${y.pad(4)}-${m.pad(2)}-${d.pad(2)} ${(rest / 3600).pad(2)}:${(rest % 3600 / 60).pad(2)}:${(rest % 60).pad(2)}"
    }

    private fun Number.pad(width: Int) = toString().padStart(width, '0')

    // Howard Hinnant's days-to-civil algorithm.
    private fun civil(daysSinceEpoch: Long): Triple<Long, Int, Int> {
        val z = daysSinceEpoch + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    private const val SECONDS_PER_DAY = 86_400L
}
