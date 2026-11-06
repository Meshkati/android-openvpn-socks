package io.github.meshkati.ovpnvless

/**
 * A profile that sets no check of the server's certificate gets
 * "remote-cert-tls server" from the app (profile_text.hpp): the certificate
 * must be marked for TLS server use. Some older PKIs don't mark it, and then
 * every connection fails with a certificate error.
 *
 * Instead of dropping the check, this offers one that works with such a PKI:
 * `verify-x509-name <name> name`, with the name from the certificate the
 * server showed. Another client's certificate from the same CA carries a
 * different name, and only the CA can issue one with the server's. The user
 * confirms the name, since a server posing as theirs at that very moment
 * would show its own.
 */
object ServerNameCheck {

    /** Starts the native log line that says the check was added (chain::Client::run). */
    const val ADDED_LOG = "profile doesn't check the server's certificate beyond its CA"

    // OpenVPN 3 with mbed TLS: the leaf certificate's dump, then the failure.
    private const val LEAF = "VERIFY OK : depth=0"
    private val FAILURES = listOf(
        "VERIFY FAIL -- bad X509 extended key usage in leaf certificate",
        "VERIFY FAIL -- bad X509 key usage in leaf certificate",
    )

    /** The name to check [profileId]'s server by instead. */
    data class Offer(val profileId: Long, val name: String)

    /** Reads one session's OpenVPN log lines. Used on the session thread only. */
    class Tracker {
        private var added = false
        private var failed = false
        private var leafName: String? = null

        fun onLog(line: String) {
            val text = line.trimStart()
            when {
                text.startsWith(ADDED_LOG) -> added = true
                text.startsWith(LEAF) -> leafName = commonName(text)
                FAILURES.any { text.startsWith(it) } -> failed = true
            }
        }

        /** The name to offer: the app's own check refused this server's certificate. */
        val name: String?
            get() = if (added && failed) leafName else null
    }

    /**
     * The CN in the dump's "subject name" line, e.g. "vpn.example.com" from
     * "subject name : C=US, O=Example, CN=vpn.example.com". Null if there is
     * none, or it has characters that can't be written into a profile
     * plainly (quotes, backslashes, control characters).
     */
    fun commonName(dump: String): String? {
        val subject = dump.lineSequence()
            .firstOrNull { it.trimStart().startsWith("subject name") }
            ?.substringAfter(':')?.trim() ?: return null
        val cn = subject.split(", ").lastOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: return null
        val usable = cn.length in 1..64 && cn == cn.trim() &&
            cn.none { it == '"' || it == '\\' || it == '\'' || it.isISOControl() }
        return cn.takeIf { usable }
    }

    /** Whether [ovpn] already checks the server by name; then there is nothing to offer. */
    fun hasNameCheck(ovpn: String): Boolean =
        ovpn.lineSequence().any { it.trim().split(Regex("\\s+")).firstOrNull() == "verify-x509-name" }

    /** [ovpn] with the check by [name] added at the end. */
    fun apply(ovpn: String, name: String): String {
        val arg = if (name.any(Char::isWhitespace)) "\"$name\"" else name
        return ovpn.trimEnd('\n', '\r') + "\nverify-x509-name $arg name\n"
    }
}
