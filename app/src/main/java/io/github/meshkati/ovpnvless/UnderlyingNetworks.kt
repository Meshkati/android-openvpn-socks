package io.github.meshkati.ovpnvless

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * The networks under the VPN (Wi-Fi, mobile data, …), tracked while an
 * OpenVPN-only session runs. In that mode the app is inside its own VPN, so
 * OpenVPN's lookups of its server's hostname would go into the tunnel, which
 * is down whenever OpenVPN reconnects; they use [best] instead
 * ([OpenVpn3.Callbacks.underlyingNetwork]).
 *
 * Not tied to the service's lock: [best] runs on OpenVPN 3's resolver thread.
 */
class UnderlyingNetworks(private val cm: ConnectivityManager) {
    private val networks = mutableMapOf<Network, NetworkCapabilities>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(networks) { networks[network] = caps }
        }

        override fun onLost(network: Network) {
            synchronized(networks) { networks.remove(network) }
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { cm.unregisterNetworkCallback(callback) }
        synchronized(networks) { networks.clear() }
    }

    /**
     * The handle of the network Android would most likely pick as the default
     * without the VPN: a validated one first, then an unmetered one (Wi-Fi
     * over mobile data). 0 when there is none, i.e. use the default.
     */
    fun best(): Long = synchronized(networks) {
        networks.maxByOrNull { (_, caps) ->
            (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 1 else 0)
        }?.key?.networkHandle ?: 0L
    }
}
