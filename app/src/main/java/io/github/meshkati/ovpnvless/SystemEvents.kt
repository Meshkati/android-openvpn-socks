package io.github.meshkati.ovpnvless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

/**
 * Writes to the app log ([VpnState]) when battery saver or doze turns on or
 * off and when the app's default network changes, so a bug report shows what
 * the phone did before a session went wrong. Started once per process ([App]).
 */
object SystemEvents {
    private var started = false

    // The last default network logged, e.g. "Wi-Fi, validated"; only changes are logged.
    @Volatile private var lastNetwork: String? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val pm = app.getSystemService(PowerManager::class.java)
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        VpnState.log("system: battery saver " + if (pm.isPowerSaveMode) "on" else "off")
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                        VpnState.log("system: doze " + if (pm.isDeviceIdleMode) "on" else "off")
                }
            }
        }
        // Not exported: Android's own broadcasts still arrive, other apps' don't.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }

        try {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                        networkChanged(describe(caps))

                    override fun onLost(network: Network) = networkChanged("none")
                }
            )
        } catch (e: RuntimeException) {
            VpnState.log("system: can't follow the default network: $e")
        }
    }

    private fun networkChanged(now: String) {
        if (now == lastNetwork) return // capabilities change often, e.g. with signal strength
        lastNetwork = now
        VpnState.log("system: default network $now")
    }

    private fun describe(caps: NetworkCapabilities): String {
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return kind + if (validated) ", validated" else ", not validated"
    }
}
