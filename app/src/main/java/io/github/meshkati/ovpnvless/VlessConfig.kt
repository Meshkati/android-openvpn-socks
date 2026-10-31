package io.github.meshkati.ovpnvless

import java.net.URLDecoder

/**
 * What the UI shows about the VLESS setting: a share link, a full Xray JSON
 * config, or text that is neither. Display only: the Go engine builds and
 * validates the real config when connecting.
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
    ) : VlessConfig

    data object Json : VlessConfig

    data object Invalid : VlessConfig

    companion object {
        /** Returns null for blank text. */
        fun parse(text: String): VlessConfig? {
            val s = text.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("{")) return Json
            if (!s.startsWith("vless://", ignoreCase = true)) return Invalid

            var rest = s.substring("vless://".length)
            val name = decode(rest.substringAfter('#', "")).ifEmpty { null }
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "")
            rest = rest.substringBefore('?').substringBefore('/')

            val at = rest.lastIndexOf('@')
            if (at <= 0) return Invalid
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

        /** Percent-decodes [s] as UTF-8. A '+' stays a '+'; a bad escape returns [s] unchanged. */
        private fun decode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}
