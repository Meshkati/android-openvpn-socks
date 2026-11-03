package io.github.meshkati.ovpnvless

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A crash from an earlier run of the app, kept until the user reports or dismisses it. */
data class Crash(val kind: Kind, val time: Long, val details: String) {

    enum class Kind(val label: String) {
        JAVA("App crash (Kotlin/Java)"),
        GO("Engine crash (Go: Xray / gVisor)"),
        NATIVE("Native crash (OpenVPN 3 or engine)"),
        ANR("App not responding (ANR)"),
    }

    /** Kept on disk as: kind, time, then the details. */
    fun encode() = "${kind.name}\n$time\n$details"

    companion object {
        fun decode(text: String): Crash? {
            val lines = text.split('\n', limit = 3)
            if (lines.size < 3) return null
            val kind = Kind.entries.firstOrNull { it.name == lines[0] } ?: return null
            val time = lines[1].toLongOrNull() ?: return null
            return Crash(kind, time, lines[2])
        }
    }
}

/**
 * Turning a [Crash] into a report the user reads and sends themselves: a
 * prefilled GitHub issue, or any app through the share sheet. The app never
 * sends it on its own.
 */
object CrashReport {
    const val ISSUE_URL = "https://github.com/${Updates.REPO}/issues/new"

    /** GitHub refuses longer new-issue links (HTTP 414). */
    const val MAX_URL = 7000

    /** Longest details kept; an ANR trace or Go dump can be much longer. */
    const val MAX_DETAILS = 24_000

    /** A Go panic or Java crash also shows up as a native crash; this close, it is the same one. */
    const val SAME_CRASH_MS = 10_000L

    /**
     * The crash to report among those found at start: the latest, except that
     * a Go or Java crash with its own stack beats the native crash it caused.
     */
    fun pick(crashes: List<Crash>): Crash? {
        val latest = crashes.maxByOrNull { it.time } ?: return null
        if (latest.kind != Crash.Kind.NATIVE) return latest
        return crashes
            .filter { it.kind == Crash.Kind.GO || it.kind == Crash.Kind.JAVA }
            .filter { latest.time - it.time in 0..SAME_CRASH_MS }
            .maxByOrNull { it.time } ?: latest
    }

    /** From an ANR's traces, the description and the main thread's stack. */
    fun anrDetails(description: String?, traces: String?): String {
        val main = traces?.lineSequence()
            ?.dropWhile { !it.startsWith("\"main\"") }
            ?.takeWhile { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()
        return listOfNotNull(description?.takeIf { it.isNotBlank() }, main.ifEmpty { null })
            .joinToString("\n\n").ifEmpty { "No details" }
    }

    /**
     * What to hide: the OpenVPN servers (every `remote`), the VLESS server and
     * its UUID, a SOCKS or HTTP proxy and its username and password, server
     * addresses in a VLESS JSON config, and the username.
     * Short values are left alone: they would match ordinary words.
     */
    fun secrets(profile: String, vless: String, username: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""^\s*remote\s+(\S+)""", RegexOption.MULTILINE).findAll(profile).forEach { out += it.groupValues[1] }
        (VlessConfig.parse(vless) as? VlessConfig.Link)?.let { link ->
            out += link.host
            link.user?.let { out += it }
        }
        val link = VlessConfig.standardProxyLink(vless.trim())
        Regex("""(?:vless|socks5?h?|https?)://([^@\s/]+)@""", RegexOption.IGNORE_CASE).find(link)?.let {
            val userInfo = it.groupValues[1]
            out += userInfo
            // A proxy's "user:pass": the password on its own too.
            if (':' in userInfo) out += userInfo.substringAfter(':')
        }
        Regex(""""(?:address|serverName)"\s*:\s*"([^"]+)"""").findAll(vless).forEach { out += it.groupValues[1] }
        out += username.trim()
        return out.filter { it.length >= 4 }.distinct().sortedByDescending { it.length }
    }

    /** What a profile holds that [secrets] looks at. */
    data class Setup(val profile: String, val vless: String, val username: String)

    /** [secrets] of every saved profile together, longest first. */
    fun secrets(setups: List<Setup>): List<String> =
        setups.flatMap { secrets(it.profile, it.vless, it.username) }.distinct().sortedByDescending { it.length }

    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")

    /** [text] with [secrets], any UUID and any IPv4 address replaced. */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        for (s in secrets) out = out.replace(s, "[server]", ignoreCase = true)
        out = UUID.replace(out, "[uuid]")
        return IPV4.replace(out, "[ip]")
    }

    data class Device(val app: String, val android: String, val model: String, val route: String)

    fun title(crash: Crash, app: String) = "Crash: ${crash.kind.label} in $app"

    /** Markdown, for a GitHub issue; also fine as plain text. */
    fun body(crash: Crash, device: Device, secrets: List<String>): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .format(Instant.ofEpochMilli(crash.time).atOffset(ZoneOffset.UTC))
        val details = redact(crash.details.take(MAX_DETAILS), secrets).trimEnd()
        return buildString {
            append("**What happened:** ${crash.kind.label}\n")
            append("**When:** $time\n")
            append("**App:** ${device.app}\n")
            append("**Android:** ${device.android}\n")
            append("**Device:** ${device.model}\n")
            append("**Route:** ${device.route}\n\n")
            append("What were you doing when it happened? (optional)\n\n\n")
            append("```text\n").append(details).append("\n```\n")
        }
    }

    /** A new-issue link with [body] filled in, cut to fit GitHub's limit. */
    fun issueUrl(title: String, body: String): String {
        fun url(b: String) = "$ISSUE_URL?title=${enc(title)}&body=${enc(b)}"
        if (url(body).length <= MAX_URL) return url(body)
        val note = "\n… cut to fit the link. Use Share in the app for the full report.\n```\n"
        var lines = body.removeSuffix("\n").removeSuffix("```").trimEnd().lines()
        var b: String
        do {
            lines = lines.dropLast(maxOf(1, lines.size / 10))
            b = lines.joinToString("\n") + note
        } while (url(b).length > MAX_URL && lines.isNotEmpty())
        return url(b)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
