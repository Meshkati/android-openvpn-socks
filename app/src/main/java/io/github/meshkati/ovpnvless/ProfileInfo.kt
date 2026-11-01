package io.github.meshkati.ovpnvless

/**
 * What the UI shows about an .ovpn profile. Display only: OpenVPN 3 parses the
 * profile itself when connecting.
 */
data class ProfileInfo(
    /** Host of the first `remote`, or null if the profile has none. */
    val host: String?,
    val port: Int,
    /** "UDP" or "TCP". */
    val protocol: String,
    /** The `static-challenge` prompt, or null if the profile doesn't ask for a code. */
    val challenge: String?,
) {
    companion object {
        private const val DEFAULT_PORT = 1194

        /** Returns null for a blank profile. */
        fun parse(profile: String): ProfileInfo? {
            if (profile.isBlank()) return null
            var host: String? = null
            var remotePort: Int? = null
            var remoteProto: String? = null
            var port = DEFAULT_PORT
            var proto = "udp"
            var challenge: String? = null
            var block: String? = null // inline block being skipped, e.g. "ca"

            for (raw in profile.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#' || line[0] == ';') continue
                if (block != null) {
                    if (line.equals("</$block>", ignoreCase = true)) block = null
                    continue
                }
                if (line.startsWith("<")) {
                    val tag = line.removePrefix("<").substringBefore('>').trim().lowercase()
                    // <connection> blocks hold remote lines, so they are read.
                    if (!tag.startsWith("/") && tag != "connection") block = tag
                    continue
                }
                val args = splitArgs(line)
                when (args[0].lowercase()) {
                    "remote" -> if (host == null && args.size >= 2) {
                        host = args[1]
                        remotePort = args.getOrNull(2)?.toIntOrNull()
                        remoteProto = args.getOrNull(3)
                    }
                    "port", "rport" -> args.getOrNull(1)?.toIntOrNull()?.let { port = it }
                    "proto" -> args.getOrNull(1)?.let { proto = it }
                    "static-challenge" -> challenge = args.getOrNull(1).orEmpty()
                }
            }
            val p = (remoteProto ?: proto).lowercase()
            return ProfileInfo(
                host = host,
                port = remotePort ?: port,
                protocol = if (p.startsWith("tcp")) "TCP" else "UDP",
                challenge = challenge,
            )
        }

        /** Splits an OpenVPN config line into words, honouring double and single quotes. */
        private fun splitArgs(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var inWord = false
            for (c in line) {
                when {
                    quote != null -> if (c == quote) quote = null else cur.append(c)
                    c == '"' || c == '\'' -> { quote = c; inWord = true }
                    c.isWhitespace() -> if (inWord) { out += cur.toString(); cur.clear(); inWord = false }
                    else -> { cur.append(c); inWord = true }
                }
            }
            if (inWord) out += cur.toString()
            return out
        }
    }
}
