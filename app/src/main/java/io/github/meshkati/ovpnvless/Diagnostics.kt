package io.github.meshkati.ovpnvless

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads what a bug report holds ([BugReport]) from the app and from Android,
 * at the moment the user asks for it: the VPN session as the app sees it and
 * as Android sees it, power saving, the networks, why the process ended
 * before, and where the session's thread is. Nothing here is sent anywhere.
 */
object Diagnostics {
    /** The thread [ChainVpnService] runs OpenVPN 3 on. */
    private const val SESSION_THREAD = "openvpn3"
    private const val MAX_EXITS = 10
    private const val MAX_FRAMES = 30

    fun snapshot(context: Context): BugReport.Snapshot {
        val zone = ZoneId.systemDefault()
        return BugReport.Snapshot(
            time = System.currentTimeMillis(),
            zone = zone,
            device = device(context, currentRoute(context)),
            sections = listOf(
                section("VPN") { vpn(context) },
                section("Power") { power(context) },
                section("Networks") { networks(context) },
                section("Earlier exits of the app") { exits(context, zone) },
                section("Session thread") { sessionThread() },
                section("Other threads", brief = false) { otherThreads() },
            ),
            log = VpnState.logEntries(),
        )
    }

    /** What a crash or bug report says about the app and phone. */
    fun device(context: Context, route: Route) = CrashReport.Device(
        app = BuildConfig.VERSION_NAME,
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        route = context.getString(
            if (route == Route.VIA_VLESS) R.string.crash_route_vless else R.string.crash_route_openvpn
        ),
    )

    /** What to hide in a report: every profile's servers, not only the one in use, since the log may be from any of them. */
    fun secrets(context: Context): List<String> =
        CrashReport.secrets(Profiles.all(context).map { CrashReport.Setup(it.ovpn, it.vless, it.username, it.password) })

    /** The running session's route, else the one the profile in use would take. */
    private fun currentRoute(context: Context): Route {
        if (VpnState.phase != VpnState.Phase.DISCONNECTED) return VpnState.route
        val profile = Profiles.active(context)
        return Route.choose(VlessConfig.parse(profile.vless), profile.useVless)
    }

    /** One part of the report; if Android refuses something, the report says so instead of failing. */
    private fun section(title: String, brief: Boolean = true, read: () -> List<String>): BugReport.Section {
        val text = try {
            read().joinToString("\n")
        } catch (e: Exception) {
            "could not read: $e"
        }
        return BugReport.Section(title, text, brief)
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun vpn(context: Context): List<String> {
        val out = mutableListOf<String>()
        out += "app state: ${VpnState.phase}" + VpnState.detail.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        out += "session route: ${VpnState.route}"
        out += "tunnel address: " + if (VpnState.tunnelAddress.isEmpty()) "none" else "set"
        if (VpnState.vlessError.isNotEmpty()) out += "VLESS error: ${VpnState.vlessError}"

        val am = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION") // still lists the app's own services
        val service = am.getRunningServices(100).firstOrNull {
            it.service.packageName == context.packageName && it.service.className == ChainVpnService::class.java.name
        }
        out += "VPN service: " + when {
            service == null -> "not running"
            else -> (if (service.foreground) "running in the foreground" else "running, not in the foreground") +
                ", for ${BugReport.duration(SystemClock.uptimeMillis() - service.activeSince)} of awake time"
        }
        // Null when this app is the one allowed to run the VPN; else another VPN app took over or it was revoked.
        out += "VPN permission held: " + yesNo(VpnService.prepare(context) == null)

        val vpnUp = context.getSystemService(ConnectivityManager::class.java).let { cm ->
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }
        out += "a VPN network is listed by Android: ${yesNo(vpnUp)}"

        val profile = Profiles.active(context)
        out += "profiles: ${Profiles.ids(context).size}"
        ProfileInfo.parse(profile.ovpn)?.let {
            out += "OpenVPN: ${it.protocol}, authenticator code: ${yesNo(it.challenge != null)}"
        }
        out += "VLESS step: " + (if (profile.useVless) "on" else "off") + ", " +
            when (val v = VlessConfig.parse(profile.vless)) {
                is VlessConfig.Link -> listOfNotNull(v.protocol, v.transport, v.security, v.flow).joinToString(" ")
                VlessConfig.Json -> "Xray JSON config"
                VlessConfig.Invalid -> if (profile.vless.isBlank()) "none set" else "not readable"
            }

        val me = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        out += "process: started ${BugReport.duration(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())}" +
            " ago, importance ${BugReport.importance(me.importance)}"
        return out
    }

    private fun power(context: Context): List<String> {
        val out = mutableListOf<String>()
        val pm = context.getSystemService(PowerManager::class.java)
        out += "battery saver: " + if (pm.isPowerSaveMode) "on" else "off"
        out += "doze (device idle): ${yesNo(pm.isDeviceIdleMode)}"
        out += "screen on: ${yesNo(pm.isInteractive)}"
        out += "battery optimization: " +
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) "off for this app (unrestricted)" else "on (optimized)"
        if (Build.VERSION.SDK_INT >= 28) {
            val am = context.getSystemService(ActivityManager::class.java)
            out += "background restricted: ${yesNo(am.isBackgroundRestricted)}"
            val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            out += "app standby bucket: ${BugReport.standbyBucket(bucket)}"
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        out += "data saver: " + when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on, this app allowed"
            else -> "off"
        }
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery ->
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0) out += "battery: ${level * 100 / scale}%, " + if (plugged) "charging" else "not charging"
        }
        // Deep sleep is the difference: the phone was awake for this much of the time since boot.
        out += "awake since boot: ${BugReport.duration(SystemClock.uptimeMillis())} of " +
            BugReport.duration(SystemClock.elapsedRealtime())
        return out
    }

    private fun networks(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val out = mutableListOf<String>()
        val default = cm.activeNetwork
        out += "default for this app: " + (default?.let { describe(cm, it) } ?: "none")
        @Suppress("DEPRECATION")
        val all = cm.allNetworks
        if (all.isEmpty()) out += "no networks"
        for (n in all) out += "- " + describe(cm, n) + if (n == default) " (default)" else ""
        return out
    }

    /** E.g. "wlan0 Wi-Fi: internet, validated". No addresses or network names. */
    private fun describe(cm: ConnectivityManager, network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "gone"
        val iface = cm.getLinkProperties(network)?.interfaceName
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "mobile data",
            NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
        ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
        val flags = buildList {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
            add(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not validated")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("captive portal")
            if (Build.VERSION.SDK_INT >= 28 && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                add("suspended")
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("metered")
        }
        return listOfNotNull(iface, transports).joinToString(" ") + ": " + flags.joinToString(", ")
    }

    /** Android's record of why the process ended before (Android 11+): killed for memory, by the user, a crash… */
    private fun exits(context: Context, zone: ZoneId): List<String> {
        if (Build.VERSION.SDK_INT < 30) return listOf("Android 11 or later keeps these")
        val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(zone)
        val infos = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, MAX_EXITS)
        if (infos.isEmpty()) return listOf("none recorded")
        return infos.map { info ->
            clock.format(Instant.ofEpochMilli(info.timestamp)) + "  " + BugReport.exitReason(info.reason) +
                ", was ${BugReport.importance(info.importance)}" +
                info.description?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        }
    }

    /** Where OpenVPN 3's thread is: stuck in native code after a disconnect would show here. */
    private fun sessionThread(): List<String> {
        val (thread, stack) = Thread.getAllStackTraces().entries.firstOrNull { it.key.name == SESSION_THREAD }
            ?: return listOf("none: no session is running")
        return listOf("\"${thread.name}\" ${thread.state}") + frames(stack)
    }

    private fun otherThreads(): List<String> {
        val all = Thread.getAllStackTraces()
        val out = mutableListOf<String>()
        all.entries.firstOrNull { it.key === android.os.Looper.getMainLooper().thread }?.let { (t, stack) ->
            out += "\"${t.name}\" ${t.state}"
            out += frames(stack)
            out += ""
        }
        out += "all threads (${all.size}):"
        out += all.keys.sortedBy { it.name }.map { "  \"${it.name}\" ${it.state}" }
        return out
    }

    private fun frames(stack: Array<StackTraceElement>): List<String> =
        stack.take(MAX_FRAMES).map { "  at $it" } +
            if (stack.size > MAX_FRAMES) listOf("  … ${stack.size - MAX_FRAMES} more") else emptyList()
}
