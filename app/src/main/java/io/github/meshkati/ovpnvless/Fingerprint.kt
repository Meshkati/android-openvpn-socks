package io.github.meshkati.ovpnvless

import java.net.URLDecoder

/**
 * The TLS fingerprint (`fp=`) of a vless:// link with `security=tls`.
 *
 * The randomized fingerprints can offer the post-quantum key exchange
 * X25519MLKEM768 without sending a key for it. Servers that prefer it, such
 * as Cloudflare's, then ask for one in a HelloRetryRequest, which uTLS can't
 * answer: every connection fails with "tls: CurvePreferences includes
 * unsupported curve". Xray picks the random fingerprint once per app
 * process, so one run of the app works and the next fails throughout.
 * "chrome" sends the key up front and doesn't hit this.
 */
object Fingerprint {

    /** What the warning offers instead. */
    const val SAFE = "chrome"

    private val RANDOMIZED = setOf("randomized", "randomizednoalpn")

    /** The link's fingerprint if it is a randomized one on a TLS link, else null. */
    fun risky(text: String): String? {
        val params = params(text) ?: return null
        if (params.firstOrNull { it.first == "security" }?.second?.lowercase() != "tls") return null
        val fp = params.firstOrNull { it.first == "fp" }?.second ?: return null
        return fp.takeIf { it.lowercase() in RANDOMIZED }
    }

    /** [text] with every `fp` set to [SAFE]. The rest of the link, the name included, stays as it was. */
    fun fix(text: String): String {
        val link = text.trim()
        if (params(link) == null) return text
        val name = link.indexOf('#').let { if (it < 0) "" else link.substring(it) }
        val beforeName = link.removeSuffix(name)
        val q = beforeName.indexOf('?')
        val query = beforeName.substring(q + 1).split('&').joinToString("&") {
            if (decode(it.substringBefore('=')) == "fp") "fp=$SAFE" else it
        }
        return beforeName.substring(0, q + 1) + query + name
    }

    /**
     * The query parameters of a vless:// link, in order, or null for other
     * text or a link without them. Like Go's url.Values.Get, which the engine
     * uses, a repeated parameter counts by its first value.
     */
    private fun params(text: String): List<Pair<String, String>>? {
        val link = text.trim()
        if (!link.startsWith("vless://", ignoreCase = true)) return null
        val beforeName = link.substringBefore('#')
        if ('?' !in beforeName) return null
        return beforeName.substringAfter('?').split('&').filter { it.isNotEmpty() }.map {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
