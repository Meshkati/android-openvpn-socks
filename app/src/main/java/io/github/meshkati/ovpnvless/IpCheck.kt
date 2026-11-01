package io.github.meshkati.ovpnvless

import android.os.SystemClock
import engine.Engine
import kotlin.concurrent.thread

/**
 * The home screen's IP check: asks ifconfig.me for this connection's public
 * IP, through Xray, the way apps' traffic leaves. The app is excluded from its
 * own VPN, so a plain request from here would go out over the real network;
 * the engine sends this one through VLESS instead. That needs the engine, so
 * it only works with [Route.VIA_VLESS].
 *
 * Its log lines also go to [VpnState]'s log, and changes notify its observers.
 */
object IpCheck {
    const val URL = "https://ifconfig.me/ip"

    enum class State { IDLE, RUNNING, DONE, FAILED }

    @Volatile
    var state = State.IDLE
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

    /** This check's log: its steps and the Xray lines logged meanwhile ("xray: "). */
    fun lines(): List<String> = synchronized(lines) { lines.toList() }

    @Synchronized
    fun start() {
        if (state == State.RUNNING) return
        val id = ++run
        synchronized(lines) { lines.clear() }
        state = State.RUNNING
        result = ""
        millis = 0
        VpnState.notifyObservers()
        val started = SystemClock.elapsedRealtime()
        thread(name = "ip-check") {
            val outcome = try {
                Result.success(Engine.checkIP(URL) { line -> add(id, line) })
            } catch (e: Exception) {
                Result.failure(e)
            }
            finish(id, outcome, SystemClock.elapsedRealtime() - started)
        }
    }

    /** Forgets the last check, e.g. when a new session starts. */
    @Synchronized
    fun reset() {
        run++
        synchronized(lines) { lines.clear() }
        state = State.IDLE
        result = ""
        millis = 0
        VpnState.notifyObservers()
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
