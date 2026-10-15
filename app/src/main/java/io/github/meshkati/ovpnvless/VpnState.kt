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
    private var observer: (() -> Unit)? = null

    @Volatile
    var phase = Phase.DISCONNECTED
        private set

    @Volatile
    var detail = ""
        private set

    fun set(phase: Phase, detail: String = "") {
        this.phase = phase
        this.detail = detail
        notifyObserver()
    }

    fun log(line: String) {
        Log.i("ovpn-vless", line)
        synchronized(lines) {
            lines.addLast(line.trimEnd())
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyObserver()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Sets the single UI observer (null to clear); it is called on the main thread. */
    fun observe(o: (() -> Unit)?) {
        main.post {
            observer = o
            o?.invoke()
        }
    }

    private fun notifyObserver() {
        main.post { observer?.invoke() }
    }
}
