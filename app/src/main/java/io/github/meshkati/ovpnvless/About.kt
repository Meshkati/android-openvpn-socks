package io.github.meshkati.ovpnvless

/** The versions shown on the About screen ([AboutActivity]). */
object About {
    const val SOURCE_URL = "https://github.com/${Updates.REPO}"

    /** The app, Xray-core and OpenVPN 3 core versions; null while not known. */
    data class Versions(val app: String, val xray: String?, val openVpn: String?)

    /**
     * The OpenVPN 3 core version from [OpenVpn3.coreVersion], which reads
     * "OpenVPN 3 core release/3.11.7, android/arm64 …": "3.11.7". Anything
     * else is returned as it is, trimmed.
     */
    fun openVpnVersion(core: String): String {
        val tag = core.trim().removePrefix("OpenVPN 3 core").trim().substringBefore(',').trim()
        return tag.removePrefix("release/").ifEmpty { core.trim() }
    }

    /** Plain text for the clipboard, for a bug report. */
    fun text(v: Versions, unknown: String) = listOf(
        "OpenVPN → VLESS ${v.app}",
        "Xray-core ${v.xray ?: unknown}",
        "OpenVPN 3 core ${v.openVpn ?: unknown}",
    ).joinToString("\n")
}
