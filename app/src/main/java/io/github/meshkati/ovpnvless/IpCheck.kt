package io.github.meshkati.ovpnvless

import android.os.SystemClock
import engine.Engine
import java.io.IOException
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * The home screen's IP check: asks ifconfig.me for the public IP that sites
 * see, the way apps' traffic leaves ([Via]):
 *
 * - [Via.VLESS]: the app is excluded from its own VPN, so the engine sends the
 *   request into Xray, which takes it through VLESS.
 * - [Via.OPENVPN]: the app is inside its own VPN, so a plain request goes
 *   through OpenVPN like any app's.
 * - [Via.DIRECT]: the VPN is off; a plain request shows the real IP.
 *
 * Its log lines also go to [VpnState]'s log, and changes notify its observers.
 */
object IpCheck {
    const val URL = "https://ifconfig.me/ip"

    private const val TIMEOUT_MS = 20_000

    enum class State { IDLE, RUNNING, DONE, FAILED }

    enum class Via {
        DIRECT, OPENVPN, VLESS;

        companion object {
            /**
             * How a check started now would go, or null while connecting or
             * disconnecting, when it would show neither IP. [route] is the
             * running session's route while one runs.
             */
            fun of(stage: Stage, route: Route): Via? = when (stage) {
                Stage.NOT_SET_UP, Stage.READY, Stage.FAILED -> DIRECT
                Stage.CONNECTED -> if (route == Route.VIA_VLESS) VLESS else OPENVPN
                Stage.CONNECTING, Stage.DISCONNECTING -> null
            }
        }
    }

    @Volatile
    var state = State.IDLE
        private set

    /** How the last check went. */
    @Volatile
    var via = Via.DIRECT
        private set

    /** The public IP after [State.DONE], the error after [State.FAILED]. */
    @Volatile
    var result = ""
        private set

    /** How long the last check took. */
    @Volatile
    var millis = 0L
        private set

    private val lines = mutableListOf<String>()

    // Bumped by start() and reset(), so a check that was reset can't write.
    private var run = 0

    /** This check's log: its steps and, through VLESS, the Xray lines logged meanwhile ("xray: "). */
    fun lines(): List<String> = synchronized(lines) { lines.toList() }

    @Synchronized
    fun start(via: Via) {
        if (state == State.RUNNING) return
        val id = ++run
        synchronized(lines) { lines.clear() }
        state = State.RUNNING
        this.via = via
        result = ""
        millis = 0
        VpnState.notifyObservers()
        val started = SystemClock.elapsedRealtime()
        thread(name = "ip-check") {
            val log = { line: String -> add(id, line) }
            val outcome = try {
                Result.success(
                    if (via == Via.VLESS) Engine.checkIP(URL) { line -> log(line) }
                    else fetch(via, started, log)
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            finish(id, outcome, SystemClock.elapsedRealtime() - started)
        }
    }

    /** Forgets the last check, e.g. when a session starts or ends. */
    @Synchronized
    fun reset() {
        run++
        synchronized(lines) { lines.clear() }
        state = State.IDLE
        result = ""
        millis = 0
        VpnState.notifyObservers()
    }

    /** A plain HTTPS request from the app ([Via.DIRECT], [Via.OPENVPN]), logged like the engine's. */
    private fun fetch(via: Via, started: Long, log: (String) -> Unit): String {
        val step = { line: String -> log("%5dms  %s".format(SystemClock.elapsedRealtime() - started, line)) }
        step(
            if (via == Via.OPENVPN) "GET $URL through OpenVPN, as apps' traffic goes"
            else "GET $URL directly, the VPN is off"
        )
        val conn = java.net.URL(URL).openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false // show a redirect instead of following it
            conn.useCaches = false
            // ifconfig.me answers command-line clients with plain text.
            conn.setRequestProperty("User-Agent", "curl/8.7.1")
            conn.setRequestProperty("Accept", "text/plain")
            // Never reuse a connection opened before the VPN came up or went down.
            conn.setRequestProperty("Connection", "close")
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw IOException(describe(e), e).also { step("failed: ${it.message}") }
            }
            conn.cipherSuite?.let { step("TLS, $it") }
            step("< HTTP $code ${conn.responseMessage.orEmpty()}".trimEnd())
            conn.headerFields.filterKeys { it != null }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (k, v) -> step("< $k: ${v.joinToString(", ")}") }
            val body = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.use { String(readAtMost(it, 4096)) }.orEmpty().trim()
            body.lines().take(20).forEach { step("< ${it.trimEnd('\r')}") }
            return try {
                answer(code, body)
            } catch (e: IOException) {
                step("failed: ${e.message}")
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is java.net.SocketTimeoutException -> "no answer in ${TIMEOUT_MS / 1000} s"
        is java.net.UnknownHostException -> "couldn't look up ${java.net.URL(URL).host}"
        else -> e.message ?: e.toString()
    }

    /** The IP address in ifconfig.me's answer; throws IOException for anything else. */
    fun answer(code: Int, body: String): String {
        if (code != HttpURLConnection.HTTP_OK) throw IOException("ifconfig.me answered HTTP $code")
        val text = body.trim()
        if (!isIpAddress(text)) {
            throw IOException("the answer is not an IP address: ${text.take(60).ifEmpty { "(empty)" }}")
        }
        return text
    }

    private val ipv4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
    private val hexGroup = Regex("[0-9A-Fa-f]{1,4}")

    /** Textual IPv4 or IPv6 (no zone), checked without a DNS lookup. */
    fun isIpAddress(text: String): Boolean = ipv4.matches(text) || isIpv6(text)

    private fun isIpv6(text: String): Boolean {
        val halves = text.split("::")
        if (halves.size > 2) return false
        val groups = halves.map { half -> if (half.isEmpty()) emptyList() else half.split(':') }
        val all = groups.flatten()
        if (all.isEmpty()) return halves.size == 2 // "::"
        // The last group may be an embedded IPv4 address, which counts as two.
        val last = all.last()
        val embedded = !text.endsWith(":") && ipv4.matches(last)
        val hex = if (embedded) all.dropLast(1) else all
        if (!hex.all { hexGroup.matches(it) }) return false
        val count = hex.size + if (embedded) 2 else 0
        return if (halves.size == 2) count < 8 else count == 8
    }

    @Synchronized
    private fun add(id: Int, line: String) {
        if (id != run) return
        synchronized(lines) { lines += line }
        // The service's engine logger already put Xray's lines in the log.
        if (LogLines.source(line) == LogLines.Source.XRAY) VpnState.notifyObservers()
        else VpnState.log("ip check: $line")
    }

    @Synchronized
    private fun finish(id: Int, outcome: Result<String>, millis: Long) {
        if (id != run) return
        state = if (outcome.isSuccess) State.DONE else State.FAILED
        result = outcome.getOrNull() ?: outcome.exceptionOrNull()?.let { it.message ?: it.toString() }.orEmpty()
        this.millis = millis
        VpnState.notifyObservers()
    }
}
