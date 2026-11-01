package io.github.meshkati.ovpnvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import engine.Engine
import kotlin.concurrent.thread

/**
 * Runs the session's [Route]. With VLESS ([Route.VIA_VLESS]), the chain:
 *
 *   apps -> this VpnService's TUN -> Xray tun inbound -> VLESS
 *        -> userspace TCP/IP stack -> socketpair -> OpenVPN 3 -> network
 *
 * OpenVPN 3 is started first. It never gets a real TUN: when it asks for one
 * (onTun) it receives a socketpair, and we pass the other end to the Go
 * engine, which dials the VLESS server through it. Only then is the device
 * TUN established, and Xray reads it.
 *
 * OpenVPN only ([Route.OPENVPN_ONLY]): apps -> this VpnService's TUN ->
 * OpenVPN 3 -> network. OpenVPN 3 asks for the TUN (onDeviceTun) and gets the
 * device TUN, built with the addresses, routes and DNS its server pushed.
 *
 * Either way this app excludes itself from the VPN so OpenVPN's own transport
 * socket goes straight to the network.
 */
class ChainVpnService : VpnService(), OpenVpn3.Callbacks {

    companion object {
        const val ACTION_CONNECT = "io.github.meshkati.ovpnvless.CONNECT"
        const val ACTION_DISCONNECT = "io.github.meshkati.ovpnvless.DISCONNECT"

        // One-time code for the profile's static-challenge. Not stored: it is
        // only valid once.
        const val EXTRA_OTP = "otp"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn"

        // Address of the device TUN. Only Xray reads this interface, so any
        // private range unused by the local network works.
        private const val TUN_ADDRESS_V4 = "172.19.0.1"

        // DNS server given to apps; their queries are carried over VLESS.
        private const val APP_DNS = "1.1.1.1"
    }

    private var session: Thread? = null

    // Only touched on the session thread (onTun runs inside OpenVpn3.run).
    private var tun: ParcelFileDescriptor? = null
    private var vlessConfig = ""

    // The device TUN is up: built for Xray (VIA_VLESS) or handed to OpenVPN
    // (OPENVPN_ONLY). Session thread only.
    private var tunnelUp = false

    // Notification text: "<OpenVPN server> → <VLESS server>".
    private var route = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
        } else {
            // ACTION_CONNECT, or the system starting us for always-on VPN.
            connect(intent?.getStringExtra(EXTRA_OTP).orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // The user revoked the permission or another VPN app took over.
        disconnect()
    }

    override fun onDestroy() {
        OpenVpn3.stop()
        super.onDestroy()
    }

    @Synchronized
    private fun connect(otp: String) {
        val settings = Settings(this)
        if (session != null) {
            startForegroundNotification()
            return
        }

        val sessionRoute = Route.choose(VlessConfig.parse(settings.vless), settings.useVless)
        route = routeText(settings, sessionRoute)
        startForegroundNotification()
        vlessConfig = if (sessionRoute == Route.VIA_VLESS) settings.vless else ""
        VpnState.startSession(sessionRoute)
        IpCheck.reset()
        VpnState.set(VpnState.Phase.CONNECTING, "starting OpenVPN")
        if (sessionRoute == Route.VIA_VLESS) {
            VpnState.log("${OpenVpn3.coreVersion()}; Xray-core ${Engine.xrayVersion()}")
            Engine.setLogger { line -> VpnState.log("xray: $line") }
        } else {
            VpnState.log("${OpenVpn3.coreVersion()}; OpenVPN only, VLESS is off")
        }

        session = thread(name = "openvpn3") {
            val error = try {
                OpenVpn3.run(
                    settings.profile, settings.username, settings.password, otp,
                    deviceTun = sessionRoute == Route.OPENVPN_ONLY, callbacks = this,
                )
            } catch (e: Throwable) {
                e.toString()
            }
            VpnState.log(error?.let { "OpenVPN error: $it" } ?: "OpenVPN session ended")
            stopEngine()
            synchronized(this) { session = null }
            VpnState.set(VpnState.Phase.DISCONNECTED, error ?: "")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    private fun disconnect() {
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        VpnState.set(VpnState.Phase.DISCONNECTING)
        OpenVpn3.stop() // run() returns and the session thread cleans up
    }

    // --- OpenVpn3.Callbacks (session thread) ------------------------------

    override fun onLog(line: String) = VpnState.log("openvpn: $line")

    override fun onEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        VpnState.log("openvpn event: $name $info")
        when (name) {
            "RECONNECTING", "RESOLVE", "WAIT", "CONNECTING", "GET_CONFIG", "ASSIGN_IP" ->
                if (VpnState.phase != VpnState.Phase.DISCONNECTING) {
                    if (VpnState.phase == VpnState.Phase.CONNECTED) updateNotification(R.string.state_connecting)
                    VpnState.set(VpnState.Phase.CONNECTING, name.lowercase())
                }
            "CONNECTED" -> if (tunnelUp) VpnState.set(VpnState.Phase.CONNECTED, info)
        }
    }

    override fun onTun(fd: Int, addresses: Array<String>, dns: Array<String>, mtu: Int): Boolean {
        VpnState.log("OpenVPN tunnel: ${addresses.joinToString()} dns=${dns.joinToString()} mtu=$mtu")
        stopEngine() // OpenVPN re-established the tunnel with new settings

        return try {
            val pfd = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(mtu)
                // IPv4 only. With no IPv6 address or route, Android blocks
                // IPv6 while the VPN is up (no leak) and apps fall back to
                // IPv4. VLESS servers often send IPv6 destinations out a
                // different way (a Cloudflare Worker uses a relay pool for
                // them), so offering IPv6 made sites see another exit IP.
                .addAddress(TUN_ADDRESS_V4, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(APP_DNS)
                // Our own sockets (OpenVPN's transport) must bypass the TUN,
                // or OpenVPN would try to tunnel through itself.
                .addDisallowedApplication(packageName)
                .establish() ?: throw IllegalStateException("VPN permission is not granted")
            tun = pfd
            Engine.start(fd, addresses.joinToString(","), dns.joinToString(","), mtu, pfd.fd, vlessConfig)
            tunnelUp(addresses)
            true
        } catch (e: Exception) {
            VpnState.setVlessError(e.message ?: e.toString())
            VpnState.log("failed to start VLESS: ${e.message}")
            tun?.close()
            tun = null
            false
        }
    }

    override fun onDeviceTun(
        addresses: Array<String>, routes: Array<String>, dns: Array<String>, searchDomains: Array<String>, mtu: Int,
    ): Int {
        VpnState.log(
            "OpenVPN tunnel: ${addresses.joinToString()} routes=${routes.joinToString()} " +
                "dns=${dns.joinToString()} domains=${searchDomains.joinToString()} mtu=$mtu"
        )
        tunnelUp = false
        return try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(mtu)
                // Our own sockets (OpenVPN's transport) must bypass the TUN,
                // or OpenVPN would try to tunnel through itself.
                .addDisallowedApplication(packageName)
            // An address family with no address or route here is blocked
            // while the VPN is up (Android's default), e.g. IPv6 when the
            // server sets up only IPv4.
            for (a in addresses.map(Cidr::parse)) builder.addAddress(a.address, a.prefixLength)
            for (r in routes.map(Cidr::parse)) builder.addRoute(r.address, r.prefixLength)
            for (server in dns) builder.addDnsServer(server)
            for (domain in searchDomains) builder.addSearchDomain(domain)
            val pfd = builder.establish() ?: throw IllegalStateException("VPN permission is not granted")
            tunnelUp(addresses)
            pfd.detachFd() // OpenVPN 3 owns it now and closes it when the session ends
        } catch (e: Exception) {
            VpnState.log("failed to set up the VPN: ${e.message}")
            -1
        }
    }

    /** The device TUN is up: the session is connected. */
    private fun tunnelUp(addresses: Array<String>) {
        tunnelUp = true
        VpnState.setTunnel(addresses.firstOrNull()?.substringBefore('/').orEmpty())
        VpnState.set(VpnState.Phase.CONNECTED, addresses.joinToString())
        updateNotification(R.string.state_connected)
    }

    private fun stopEngine() {
        tunnelUp = false
        try {
            Engine.stop()
        } catch (e: Exception) {
            VpnState.log("engine stop: ${e.message}")
        }
        tun?.close()
        tun = null
    }

    private fun routeText(settings: Settings, route: Route): String {
        val openVpn = ProfileInfo.parse(settings.profile)?.host ?: getString(R.string.hop_openvpn)
        if (route == Route.OPENVPN_ONLY) return openVpn
        val vless = (VlessConfig.parse(settings.vless) as? VlessConfig.Link)?.host ?: getString(R.string.hop_vless)
        return getString(R.string.notification_route, openVpn, vless)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification(
            if (VpnState.phase == VpnState.Phase.CONNECTED) R.string.state_connected else R.string.state_connecting
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Replaces the notification's title, e.g. when the tunnel comes up. */
    private fun updateNotification(title: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title))
    }

    private fun buildNotification(title: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ChainVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(title))
            .setContentText(route)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
