package io.github.meshkati.ovpnvless

import java.net.URLDecoder
import java.util.Base64

/**
 * What the UI shows about the VLESS setting: a vless://, socks:// or http(s)://
 * link, a full Xray JSON config, or text that is none of these. Display only:
 * the Go engine builds and validates the real config when connecting.
 */
sealed interface VlessConfig {

    data class Link(
        val host: String,
        val port: Int,
        /** Readable transport name, e.g. "WebSocket". */
        val transport: String,
        /** WebSocket/HTTPUpgrade/XHTTP path or gRPC service name. */
        val path: String?,
        /** "None", "TLS", "REALITY" or the raw value. */
        val security: String,
        /** "Vision" or the raw flow. */
        val flow: String?,
        val sni: String?,
        /** The link's #fragment. */
        val name: String?,
        /** "VLESS", "SOCKS" or "HTTP". */
        val protocol: String = VLESS,
        /** A SOCKS or HTTP proxy's username. */
        val user: String? = null,
    ) : VlessConfig

    data object Json : VlessConfig

    data object Invalid : VlessConfig

    companion object {
        const val VLESS = "VLESS"
        const val SOCKS = "SOCKS"
        const val HTTP = "HTTP"

        /** Proxy schemes the engine takes besides vless://, and what they are. */
        private val PROXIES = mapOf(
            "socks" to SOCKS, "socks5" to SOCKS, "socks5h" to SOCKS, "http" to HTTP, "https" to HTTP,
        )

        /** Returns null for blank text. */
        fun parse(text: String): VlessConfig? {
            val s = text.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("{")) return Json
            val scheme = s.substringBefore("://", "").lowercase()
            if (scheme != "vless" && scheme !in PROXIES) return Invalid

            var rest = s.substring(scheme.length + "://".length)
            val name = decode(rest.substringAfter('#', "")).ifEmpty { null }
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "")
            rest = rest.substringBefore('?').substringBefore('/')

            val at = rest.lastIndexOf('@')
            // A VLESS link needs its UUID; a proxy's credentials are optional.
            if (scheme == "vless" && at <= 0) return Invalid
            val userInfo = if (at > 0) rest.substring(0, at) else ""
            val hostPort = rest.substring(at + 1)
            val host: String
            val portText: String
            if (hostPort.startsWith("[")) {
                host = hostPort.substringAfter('[').substringBefore(']')
                portText = hostPort.substringAfter("]:", "")
            } else {
                host = hostPort.substringBeforeLast(':')
                portText = hostPort.substringAfterLast(':', "")
            }
            val port = portText.toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) return Invalid

            val params = query.split('&').filter { it.isNotEmpty() }.associate {
                decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
            }
            PROXIES[scheme]?.let { protocol ->
                val tls = scheme == "https"
                return Link(
                    host = host,
                    port = port,
                    transport = "TCP",
                    path = null,
                    security = if (tls) "TLS" else "None",
                    flow = null,
                    sni = if (tls) params["sni"]?.ifEmpty { null } else null,
                    name = name,
                    protocol = protocol,
                    user = proxyUser(userInfo),
                )
            }
            val type = params["type"].orEmpty().ifEmpty { "tcp" }.lowercase()
            val transport = when (type) {
                "tcp", "raw" -> "TCP"
                "ws" -> "WebSocket"
                "grpc" -> "gRPC"
                "httpupgrade" -> "HTTPUpgrade"
                "xhttp", "splithttp" -> "XHTTP"
                "kcp", "mkcp" -> "mKCP"
                else -> type
            }
            val path = when (type) {
                "grpc" -> params["serviceName"]
                "ws", "httpupgrade", "xhttp", "splithttp" -> params["path"]
                else -> null
            }
            val security = when (val sec = params["security"].orEmpty().lowercase()) {
                "", "none" -> "None"
                "tls" -> "TLS"
                "reality" -> "REALITY"
                else -> sec
            }
            val flow = params["flow"]?.let { if (it == "xtls-rprx-vision") "Vision" else it }
            return Link(
                host = host,
                port = port,
                transport = transport,
                path = path?.ifEmpty { null },
                security = security,
                flow = flow?.ifEmpty { null },
                sni = (params["sni"] ?: params["serverName"])?.ifEmpty { null },
                name = name,
            )
        }

        /**
         * The username in a proxy link's "user:pass", which v2rayN and v2rayNG
         * share base64-encoded, as the engine reads it.
         */
        private fun proxyUser(userInfo: String): String? {
            if (userInfo.isEmpty()) return null
            if (':' in userInfo) return decode(userInfo.substringBefore(':')).ifEmpty { null }
            val decoded = base64(userInfo)
            if (decoded != null && ':' in decoded) return decoded.substringBefore(':').ifEmpty { null }
            return decode(userInfo).ifEmpty { null }
        }

        private fun base64(s: String): String? {
            val t = s.trimEnd('=')
            for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
                try {
                    val padded = t + "=".repeat((4 - t.length % 4) % 4)
                    return String(decoder.decode(padded), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    // Try the next alphabet.
                }
            }
            return null
        }

        /** Percent-decodes [s] as UTF-8. A '+' stays a '+'; a bad escape returns [s] unchanged. */
        private fun decode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}
