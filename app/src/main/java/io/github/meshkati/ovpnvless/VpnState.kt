package io.github.meshkati.ovpnvless

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Status and log of the VPN service, shared with the UI (same process). */
object VpnState {
    enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    private const val MAX_LINES = 400
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    // Only touched on the main thread.
    private val observers = mutableListOf<() -> Unit>()

    @Volatile
    var phase = Phase.DISCONNECTED
        private set

    /**
     * While connecting: the last OpenVPN event, lower-cased. After a
     * disconnect: the error, or empty for a clean exit.
     */
    @Volatile
    var detail = ""
        private set

    /** This session's OpenVPN tunnel address without prefix, e.g. "10.8.0.6", or empty. */
    @Volatile
    var tunnelAddress = ""
        private set

    /** The route of the current or last session. */
    @Volatile
    var route = Route.VIA_VLESS
        private set

    /** Why this session's VLESS engine failed to start, or empty. */
    @Volatile
    var vlessError = ""
        private set

    fun set(phase: Phase, detail: String = "") {
        this.phase = phase
        this.detail = detail
        notifyObservers()
    }

    /** Clears the per-session fields when a new session starts. */
    fun startSession(route: Route) {
        this.route = route
        tunnelAddress = ""
        vlessError = ""
    }

    fun setTunnel(address: String) {
        tunnelAddress = address
        vlessError = ""
    }

    fun setVlessError(message: String) {
        vlessError = message
    }

    fun log(line: String) {
        Log.i("ovpn-vless", line)
        synchronized(lines) {
            lines.addLast(line.trimEnd())
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyObservers()
    }

    fun logLines(): List<String> = synchronized(lines) { lines.toList() }

    /** Adds a UI observer; it is called on the main thread, once right away. */
    fun addObserver(o: () -> Unit) {
        main.post {
            if (o !in observers) observers += o
            o()
        }
    }

    fun removeObserver(o: () -> Unit) {
        main.post { observers -= o }
    }

    private fun notifyObservers() {
        main.post { observers.toList().forEach { it() } }
    }
}
