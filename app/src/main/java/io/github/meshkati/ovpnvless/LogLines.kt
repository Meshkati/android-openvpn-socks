package io.github.meshkati.ovpnvless

/** Classifies [VpnState] log lines by the prefix the service gives them. */
object LogLines {
    enum class Source { OPENVPN, XRAY, APP }

    fun source(line: String): Source = when {
        line.startsWith("xray", ignoreCase = true) -> Source.XRAY
        line.startsWith("openvpn", ignoreCase = true) -> Source.OPENVPN
        else -> Source.APP
    }

    fun isError(line: String): Boolean =
        line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true)
}
