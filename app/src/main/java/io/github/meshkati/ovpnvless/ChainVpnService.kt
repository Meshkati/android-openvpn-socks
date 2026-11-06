package io.github.meshkati.ovpnvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * With VLESS this app excludes itself from the VPN, so OpenVPN's transport
 * socket goes straight to the network. With OpenVPN only it stays inside,
 * so its own requests (the IP check) use the tunnel like other apps': OpenVPN's
 * socket is protected instead (onProtect), and its DNS lookups of the server
 * use the network under the VPN (underlyingNetwork).
 *
 * A session runs the profile in use when it starts ([Profiles]). Switching
 * profiles while connected restarts it with the new one (ACTION_RECONNECT).
 *
 * The notification can pause the VPN (ACTION_PAUSE): the session ends but the
 * service stays in the foreground, its notification offering to resume
 * (ACTION_CONNECT). While connected it also runs the home screen's IP check
 * (ACTION_CHECK_IP) and shows the answer.
 */
class ChainVpnService : VpnService(), OpenVpn3.Callbacks {

    companion object {
        const val ACTION_CONNECT = "io.github.meshkati.ovpnvless.CONNECT"
        const val ACTION_DISCONNECT = "io.github.meshkati.ovpnvless.DISCONNECT"

        /** Ends the running session, then connects again with the profile in use. */
        const val ACTION_RECONNECT = "io.github.meshkati.ovpnvless.RECONNECT"

        /** Ends the session but keeps the notification, which can connect again. */
        const val ACTION_PAUSE = "io.github.meshkati.ovpnvless.PAUSE"

        /** Runs the home screen's IP check ([IpCheck]) through the tunnel. */
        const val ACTION_CHECK_IP = "io.github.meshkati.ovpnvless.CHECK_IP"

        // One-time code for the profile's static-challenge. Not stored: it is
        // only valid once.
        const val EXTRA_OTP = "otp"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn"

        // Request codes of the notification's PendingIntents.
        private const val REQUEST_OPEN = 0
        private const val REQUEST_DISCONNECT = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_RESUME = 3
        private const val REQUEST_CHECK_IP = 4

        // Address of the device TUN. Only Xray reads this interface, so any
        // private range unused by the local network works.
        private const val TUN_ADDRESS_V4 = "172.19.0.1"

        // DNS server given to apps; their queries are carried over VLESS.
        private const val APP_DNS = "1.1.1.1"
    }

    @Volatile
    private var session: Thread? = null

    // Set by ACTION_RECONNECT: connect again once the session ends.
    private var reconnecting = false

    // Set by ACTION_PAUSE: once the session ends, stay in the foreground with
    // a notification that can resume.
    @Volatile
    private var paused = false

    // Held while posting or removing the notification, so an update can't
    // bring it back once the session removed it. Not the service's own lock:
    // that one is held while calling OpenVpn3.stop(), and the session thread
    // updates the notification.
    private val notificationLock = Any()
    private val main = Handler(Looper.getMainLooper())

    // Only touched on the session thread (onTun runs inside OpenVpn3.run).
    private var tun: ParcelFileDescriptor? = null
    private var vlessConfig = ""

    // The device TUN is up: built for Xray (VIA_VLESS) or handed to OpenVPN
    // (OPENVPN_ONLY). Session thread only.
    private var tunnelUp = false

    // This session's OpenVPN log, read for a server name to check by. Session thread.
    private var nameCheck = ServerNameCheck.Tracker()

    // Notification text: "<OpenVPN server> → <VLESS server>".
    private var route = ""

    // Added to the notification's title, once there is more than one profile.
    private var profileName = ""

    // Tracked during OpenVPN-only sessions; see underlyingNetwork().
    private val underlying by lazy { UnderlyingNetworks(getSystemService(ConnectivityManager::class.java)) }

    // What the notification last showed of the IP check; see onIpCheckChanged().
    private var shownIp: Pair<IpCheck.State, String>? = null
    private val ipObserver = { onIpCheckChanged() }

    override fun onCreate() {
        super.onCreate()
        VpnState.addObserver(ipObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_RECONNECT -> reconnect()
            ACTION_PAUSE -> pause()
            ACTION_CHECK_IP -> checkIp()
            // ACTION_CONNECT, or the system starting us for always-on VPN.
            else -> connect(intent?.getStringExtra(EXTRA_OTP).orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // The user revoked the permission or another VPN app took over.
        disconnect()
    }

    override fun onDestroy() {
        VpnState.removeObserver(ipObserver)
        OpenVpn3.stop()
        super.onDestroy()
    }

    @Synchronized
    private fun connect(otp: String) {
        if (session != null) {
            startForegroundNotification()
            return
        }
        paused = false

        val profile = Profiles.active(this)
        val sessionRoute = Route.choose(VlessConfig.parse(profile.vless), profile.useVless)
        route = routeText(profile, sessionRoute)
        profileName = if (Profiles.ids(this).size > 1) profile.displayName(this) else ""
        startForegroundNotification()
        vlessConfig = if (sessionRoute == Route.VIA_VLESS) profile.vless else ""
        VpnState.startSession(sessionRoute)
        nameCheck = ServerNameCheck.Tracker()
        IpCheck.reset()
        if (sessionRoute == Route.OPENVPN_ONLY) underlying.start()
        VpnState.set(VpnState.Phase.CONNECTING, "starting OpenVPN")
        if (sessionRoute == Route.VIA_VLESS) {
            Crashes.watchEngine(this)
            VpnState.log("${OpenVpn3.coreVersion()}; Xray-core ${Engine.xrayVersion()}")
            Engine.setLogger { line -> VpnState.log("xray: $line") }
        } else {
            VpnState.log("${OpenVpn3.coreVersion()}; OpenVPN only, VLESS is off")
        }

        session = thread(name = "openvpn3") {
            val error = try {
                OpenVpn3.run(
                    profile.ovpn, profile.username, profile.password, otp,
                    deviceTun = sessionRoute == Route.OPENVPN_ONLY, callbacks = this,
                )
            } catch (e: Throwable) {
                e.toString()
            }
            VpnState.log(error?.let { "OpenVPN error: $it" } ?: "OpenVPN session ended")
            // Before the phase changes, so the home screen shows it with the failure.
            nameCheck.name?.let { VpnState.setNameCheckOffer(ServerNameCheck.Offer(profile.id, it)) }
            stopEngine()
            underlying.stop()
            val (again, pausing) = synchronized(this) {
                session = null
                (reconnecting to paused).also { reconnecting = false }
            }
            // A result from the tunnel would read as the IP without the VPN.
            IpCheck.reset()
            if (again) {
                // Still in the foreground: connect() replaces the notification.
                main.post { connect("") }
                return@thread
            }
            if (pausing) {
                // Asked for: not a failure. Stay in the foreground, offering to resume.
                VpnState.set(VpnState.Phase.DISCONNECTED)
                updateNotification()
                return@thread
            }
            VpnState.set(VpnState.Phase.DISCONNECTED, error ?: "")
            removeNotification()
            stopSelf()
        }
    }

    /** Ends the session and starts a new one with the profile in use ([switchProfile]). */
    @Synchronized
    private fun reconnect() {
        if (session == null) {
            connect("")
            return
        }
        startForegroundNotification()
        reconnecting = true
        VpnState.set(VpnState.Phase.DISCONNECTING)
        OpenVpn3.stop() // run() returns, and the session thread connects again
    }

    /** Ends the session, keeping the notification so it can resume (ACTION_CONNECT). */
    @Synchronized
    private fun pause() {
        // Nothing to pause, or already on its way out (a stale notification's button).
        if (session == null || paused || VpnState.phase == VpnState.Phase.DISCONNECTING) return
        paused = true
        reconnecting = false
        VpnState.set(VpnState.Phase.DISCONNECTING)
        updateNotification()
        OpenVpn3.stop() // run() returns, and the session thread keeps the notification
    }

    /** The home screen's IP check, from the notification: only while connected. */
    private fun checkIp() {
        if (VpnState.phase != VpnState.Phase.CONNECTED) return
        IpCheck.Via.of(Stage.CONNECTED, VpnState.route)?.let(IpCheck::start)
    }

    @Synchronized
    private fun disconnect() {
        reconnecting = false
        paused = false
        if (session == null) {
            removeNotification()
            stopSelf()
            return
        }
        VpnState.set(VpnState.Phase.DISCONNECTING)
        OpenVpn3.stop() // run() returns and the session thread cleans up
    }

    // --- OpenVpn3.Callbacks (session thread) ------------------------------

    override fun onLog(line: String) {
        nameCheck.onLog(line)
        VpnState.log("openvpn: $line")
    }

    override fun onEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        VpnState.log("openvpn event: $name $info")
        when (name) {
            "RECONNECTING", "RESOLVE", "WAIT", "CONNECTING", "GET_CONFIG", "ASSIGN_IP" ->
                if (VpnState.phase != VpnState.Phase.DISCONNECTING) {
                    val wasConnected = VpnState.phase == VpnState.Phase.CONNECTED
                    VpnState.set(VpnState.Phase.CONNECTING, name.lowercase())
                    if (wasConnected) updateNotification()
                }
            "CONNECTED" -> if (tunnelUp) VpnState.set(VpnState.Phase.CONNECTED, info)
        }
    }

    override fun onTun(fd: Int, addresses: Array<String>, dns: Array<String>, mtu: Int): Boolean {
        VpnState.log("OpenVPN tunnel: ${addresses.joinToString()} dns=${dns.joinToString()} mtu=$mtu")

        // Called again when OpenVPN re-establishes the tunnel with new settings.
        // The new TUN is built before the old one is closed: Android then moves
        // apps' traffic straight to the new one. Closed first, there would be
        // a moment with no VPN, and apps' traffic would leave over the real
        // network (unless "Block connections without VPN" is on).
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = Builder()
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
            stopEngine() // the old engine and TUN, now that apps' traffic goes to the new one
            tun = pfd
            Engine.start(fd, addresses.joinToString(","), dns.joinToString(","), mtu, pfd.fd, vlessConfig)
            tunnelUp(addresses)
            true
        } catch (e: Exception) {
            VpnState.setVlessError(e.message ?: e.toString())
            VpnState.log("failed to start VLESS: ${e.message}")
            stopEngine() // closes tun, the old TUN or the new one
            pfd?.close() // the new one, if it failed before it became tun
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
            // This app is not excluded: its own requests (the IP check, the
            // update check) go through OpenVPN like other apps'. OpenVPN's
            // transport socket is protected instead (onProtect).
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

    override fun onProtect(fd: Int): Boolean = protect(fd)

    override fun underlyingNetwork(): Long = underlying.best()

    /** The device TUN is up: the session is connected. */
    private fun tunnelUp(addresses: Array<String>) {
        tunnelUp = true
        VpnState.setTunnel(addresses.firstOrNull()?.substringBefore('/').orEmpty())
        VpnState.set(VpnState.Phase.CONNECTED, addresses.joinToString())
        updateNotification()
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

    private fun routeText(profile: Profile, route: Route): String {
        val openVpn = ProfileInfo.parse(profile.ovpn)?.host ?: getString(R.string.hop_openvpn)
        if (route == Route.OPENVPN_ONLY) return openVpn
        val vless = (VlessConfig.parse(profile.vless) as? VlessConfig.Link)?.host ?: getString(R.string.hop_vless)
        return getString(R.string.notification_route, openVpn, vless)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Redraws the notification from [VpnState] and [IpCheck], e.g. when the tunnel comes up. */
    private fun updateNotification() {
        synchronized(notificationLock) {
            // Not after the session ended for good: that would bring back the notification it removed.
            if (session == null && !paused) return
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun removeNotification() {
        synchronized(notificationLock) { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    /** [VpnState]'s observer (main thread): shows the IP check's progress and answer. */
    private fun onIpCheckChanged() {
        val ip = IpCheck.state to IpCheck.result
        if (ip == shownIp) return // the observers also run for every log line
        shownIp = ip
        updateNotification()
    }

    private fun buildNotification(): Notification {
        val phase = VpnState.phase
        val connected = phase == VpnState.Phase.CONNECTED
        val title = when {
            paused && session == null -> R.string.state_paused
            paused -> R.string.state_pausing
            connected -> R.string.state_connected
            else -> R.string.state_connecting
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(
                if (profileName.isEmpty()) getString(title)
                else getString(R.string.notification_title, getString(title), profileName)
            )
            .setContentText(route)
            .setContentIntent(openApp(connect = false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (connected) {
            // The IP check's answer, in the header next to the app's name.
            when (IpCheck.state) {
                IpCheck.State.IDLE -> {}
                IpCheck.State.RUNNING -> builder.setSubText(getString(R.string.notification_ip_checking))
                IpCheck.State.DONE -> builder.setSubText(getString(R.string.notification_ip, IpCheck.result))
                IpCheck.State.FAILED -> builder.setSubText(getString(R.string.notification_ip_failed))
            }
        }

        when {
            paused && session == null -> builder.addAction(action(R.string.resume, resumeIntent()))
            paused -> {}
            phase == VpnState.Phase.CONNECTING || connected ->
                builder.addAction(action(R.string.pause, serviceIntent(REQUEST_PAUSE, ACTION_PAUSE)))
        }
        if (connected && !paused) {
            builder.addAction(action(R.string.check_ip, serviceIntent(REQUEST_CHECK_IP, ACTION_CHECK_IP)))
        }
        builder.addAction(action(R.string.disconnect, serviceIntent(REQUEST_DISCONNECT, ACTION_DISCONNECT)))
        return builder.build()
    }

    private fun action(label: Int, intent: PendingIntent) =
        Notification.Action.Builder(null, getString(label), intent).build()

    private fun serviceIntent(request: Int, action: String): PendingIntent = PendingIntent.getService(
        this, request, Intent(this, ChainVpnService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Connects again with the profile in use. One that asks for an
     * authenticator code opens the home screen instead, which asks for it.
     */
    private fun resumeIntent(): PendingIntent {
        val info = ProfileInfo.parse(Profiles.active(this).ovpn)
        if (info == null || info.challenge != null) return openApp(connect = true)
        return PendingIntent.getForegroundService(
            this, REQUEST_RESUME,
            Intent(this, ChainVpnService::class.java).setAction(ACTION_CONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(connect: Boolean): PendingIntent = PendingIntent.getActivity(
        this, if (connect) REQUEST_RESUME else REQUEST_OPEN,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONNECT, connect),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
