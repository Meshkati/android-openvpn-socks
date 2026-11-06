package io.github.meshkati.ovpnvless

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import io.github.meshkati.ovpnvless.WidgetState.Action
import io.github.meshkati.ovpnvless.WidgetState.Button
import io.github.meshkati.ovpnvless.WidgetState.Dot
import io.github.meshkati.ovpnvless.WidgetState.Ip
import io.github.meshkati.ovpnvless.WidgetState.Meta

/** The 1×1 widget: the connect button, with a dot for each step under it. */
class ToggleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
}

/** The 4×2 widget: the connect button and both steps of the route, like the home screen's route card. */
class ChainWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.refresh(context)
}

/**
 * Draws both widgets from [VpnState]. [App] calls [onVpnStateChanged] for
 * every change; the widgets are redrawn only when what they show changed, as
 * the observers also run for every log line. [refresh] also re-reads the
 * settings and the VPN permission, which [VpnState] knows nothing about.
 *
 * Main thread only.
 */
object Widgets {

    // Request codes: PendingIntents to the same component differ only by these.
    private const val REQUEST_CONNECT = 10
    private const val REQUEST_DISCONNECT = 11
    private const val REQUEST_OPEN_AND_CONNECT = 12
    private const val REQUEST_OPEN = 13

    /** Narrower than this, the 4×2 widget leaves out the server names. */
    private const val SERVER_NAMES_MIN_WIDTH_DP = 260

    private var setup: WidgetState.Setup? = null
    private var hosts = Hosts(null, null, null)
    private var phase: VpnState.Phase? = null
    private var shown: Pair<WidgetState, String>? = null

    /**
     * The OpenVPN and VLESS server names, shown on the 4×2 widget, and the
     * profile's name, shown under its button once there is more than one profile.
     */
    private data class Hosts(val openVpn: String?, val vless: String?, val profile: String?)

    /** Re-reads the settings and redraws every widget. */
    fun refresh(context: Context) {
        readSetup(context.applicationContext)
        draw(context.applicationContext, force = true)
    }

    fun onVpnStateChanged(context: Context) {
        val app = context.applicationContext
        // A session starting or ending can change the VPN permission (another
        // VPN app took over); reading it costs a call to the system, so only then.
        if (VpnState.phase != phase || setup == null) readSetup(app)
        draw(app, force = false)
    }

    private fun readSetup(context: Context) {
        val active = Profiles.active(context)
        val profile = ProfileInfo.parse(active.ovpn)
        val vless = VlessConfig.parse(active.vless)
        setup = WidgetState.Setup(
            hasProfile = profile != null,
            needsCode = profile?.challenge != null,
            vless = vless,
            useVless = active.useVless,
            vpnAllowed = VpnService.prepare(context) == null,
        )
        hosts = Hosts(
            openVpn = profile?.let { it.host ?: context.getString(R.string.profile_no_server) },
            vless = vless?.let { vlessName(context, it) },
            profile = if (Profiles.ids(context).size > 1) active.displayName(context) else null,
        )
        phase = VpnState.phase
    }

    private fun draw(context: Context, force: Boolean) {
        val setup = setup ?: return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val state = WidgetState.of(
            VpnState.phase, VpnState.detail, VpnState.vlessError, VpnState.route, setup, IpCheck.state,
        )
        // Texts that can change while the state stays the same.
        val texts = listOf(VpnState.tunnelAddress, IpCheck.result, hosts).joinToString("\u0000")
        if (!force && shown == state to texts) return
        shown = state to texts

        for (id in manager.getAppWidgetIds(ComponentName(context, ToggleWidget::class.java))) {
            manager.updateAppWidget(id, toggleViews(context, state))
        }
        for (id in manager.getAppWidgetIds(ComponentName(context, ChainWidget::class.java))) {
            val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            manager.updateAppWidget(id, chainViews(context, state, showHosts = width == 0 || width >= SERVER_NAMES_MIN_WIDTH_DP))
        }
    }

    // --- 1×1 ---------------------------------------------------------------

    private fun toggleViews(context: Context, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_toggle)
        bindButton(context, views, state)
        views.setOnClickPendingIntent(R.id.widget_root, buttonIntent(context, state.action))
        views.setImageViewResource(R.id.mini_openvpn, miniDot(state.openVpn.dot))
        views.setImageViewResource(R.id.mini_vless, miniDot(state.vless.dot))
        views.setImageViewResource(R.id.mini_line, line(state.chainWhole))
        views.setContentDescription(R.id.widget_root, toggleDescription(context, state))
        return views
    }

    private fun toggleDescription(context: Context, state: WidgetState): String {
        val status = context.getString(stateText(state))
        val action = context.getString(actionText(state))
        return context.getString(R.string.widget_toggle_description, status, action)
    }

    // --- 4×2 ---------------------------------------------------------------

    private fun chainViews(context: Context, state: WidgetState, showHosts: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_chain)
        bindButton(context, views, state)
        views.setOnClickPendingIntent(R.id.widget_button, buttonIntent(context, state.action))
        views.setContentDescription(R.id.widget_button, context.getString(actionText(state)))
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, connect = false))

        views.setTextViewText(R.id.widget_state, context.getString(stateText(state)))
        setTextColor(context, views, R.id.widget_state, when (state.button) {
            Button.BUSY -> R.color.warn
            Button.ERROR -> R.color.err
            else -> R.color.ink
        })
        views.setViewVisibility(R.id.widget_profile, if (hosts.profile != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_profile, hosts.profile.orEmpty())

        bindHop(context, views, state.openVpn, R.id.hop_openvpn_dot, R.id.hop_openvpn_icon,
            R.id.hop_openvpn_value, R.id.hop_openvpn_meta, hosts.openVpn, showHosts)
        bindHop(context, views, state.vless, R.id.hop_vless_dot, R.id.hop_vless_icon,
            R.id.hop_vless_value, R.id.hop_vless_meta, hosts.vless, showHosts)
        val joint = line(state.chainWhole)
        views.setImageViewResource(R.id.line_openvpn, joint)
        views.setImageViewResource(R.id.line_between, joint)
        views.setImageViewResource(R.id.line_vless, joint)
        bindIp(context, views, state.ip)
        return views
    }

    private fun bindHop(
        context: Context, views: RemoteViews, hop: WidgetState.Hop,
        dotId: Int, iconId: Int, valueId: Int, metaId: Int, host: String?, showHosts: Boolean,
    ) {
        views.setImageViewResource(dotId, when (hop.dot) {
            Dot.OK -> R.drawable.bg_dot_ok
            Dot.BUSY -> R.drawable.bg_dot_busy
            Dot.ERROR -> R.drawable.bg_dot_err
            Dot.IDLE, Dot.OFF -> R.drawable.widget_dot_idle
        })
        val icon = when (hop.dot) {
            Dot.OK -> R.drawable.ic_widget_check
            Dot.ERROR -> R.drawable.ic_widget_cross
            else -> 0
        }
        views.setViewVisibility(iconId, if (icon == 0) View.GONE else View.VISIBLE)
        if (icon != 0) views.setImageViewResource(iconId, icon)

        views.setViewVisibility(valueId, if (showHosts && host != null) View.VISIBLE else View.GONE)
        views.setTextViewText(valueId, host.orEmpty())
        setTextColor(context, views, valueId, if (hop.dot == Dot.OFF) R.color.muted else R.color.ink)

        views.setTextViewText(metaId, metaText(context, hop))
        setTextColor(context, views, metaId, when {
            hop.dot == Dot.OK -> R.color.accent
            hop.dot == Dot.BUSY || hop.meta == Meta.CODE_NEEDED -> R.color.warn
            hop.dot == Dot.ERROR -> R.color.err
            else -> R.color.muted
        })
    }

    private fun bindIp(context: Context, views: RemoteViews, ip: Ip) {
        views.setViewVisibility(R.id.widget_ip, if (ip == Ip.NONE) View.GONE else View.VISIBLE)
        if (ip == Ip.NONE) return
        views.setTextViewCompoundDrawablesRelative(R.id.widget_ip, 0, 0, when (ip) {
            Ip.OK -> R.drawable.ic_widget_ip_ok
            Ip.FAILED -> R.drawable.ic_widget_ip_failed
            else -> 0
        }, 0)
        views.setTextViewText(R.id.widget_ip, context.getString(
            if (ip == Ip.CHECKING) R.string.widget_ip_checking else R.string.widget_ip
        ))
        setTextColor(context, views, R.id.widget_ip, when (ip) {
            Ip.OK -> R.color.accent
            Ip.FAILED -> R.color.err
            else -> R.color.muted
        })
        views.setContentDescription(R.id.widget_ip, when (ip) {
            Ip.OK -> context.getString(R.string.widget_ip_ok_description, IpCheck.result)
            Ip.FAILED -> context.getString(R.string.widget_ip_failed_description)
            else -> context.getString(R.string.ip_checking)
        })
    }

    private fun metaText(context: Context, hop: WidgetState.Hop): String = when (hop.meta) {
        Meta.NOT_SET_UP -> context.getString(R.string.widget_not_set_up)
        Meta.NOT_STARTED -> context.getString(R.string.hop_not_started)
        Meta.CODE_NEEDED -> context.getString(R.string.hop_code_needed)
        Meta.CONNECTING -> context.getString(R.string.hop_connecting)
        Meta.RECONNECTING -> context.getString(R.string.hop_reconnecting)
        Meta.WAITING -> context.getString(R.string.hop_waiting)
        Meta.CONNECTED -> VpnState.tunnelAddress.let {
            if (it.isEmpty()) context.getString(R.string.hop_connected)
            else context.getString(R.string.hop_connected_address, it)
        }
        Meta.CONNECTED_ALL_APPS -> context.getString(R.string.hop_connected_all_apps)
        Meta.DISCONNECTING -> context.getString(R.string.state_disconnecting)
        Meta.STOPPED -> context.getString(R.string.hop_stopped)
        Meta.FAILED -> context.getString(failureShort(hop.failure))
        Meta.VLESS_FAILED -> context.getString(R.string.hop_vless_failed)
        Meta.VLESS_INVALID -> context.getString(R.string.vless_invalid_meta)
        Meta.VLESS_OFF -> context.getString(R.string.hop_vless_off)
        Meta.VLESS_NOT_ADDED -> context.getString(R.string.widget_vless_not_added)
    }

    // --- both --------------------------------------------------------------

    private fun bindButton(context: Context, views: RemoteViews, state: WidgetState) {
        views.setImageViewResource(R.id.widget_button_bg, when (state.button) {
            Button.OFF, Button.LOCKED -> R.drawable.widget_button_off
            Button.BUSY -> R.drawable.widget_button_busy
            Button.ON -> R.drawable.widget_button_on
            Button.HALF -> R.drawable.widget_button_half
            Button.ERROR -> R.drawable.widget_button_error
        })
        views.setImageViewResource(R.id.widget_button_icon, when (state.button) {
            Button.OFF -> R.drawable.ic_widget_power
            Button.BUSY -> R.drawable.ic_widget_power_busy
            Button.ON -> R.drawable.ic_widget_power_on
            Button.HALF -> R.drawable.ic_widget_power_half
            Button.ERROR -> R.drawable.ic_widget_power_error
            Button.LOCKED -> R.drawable.ic_widget_lock
        })
        views.setViewVisibility(R.id.widget_spinner, if (state.button == Button.BUSY) View.VISIBLE else View.GONE)
    }

    private fun buttonIntent(context: Context, action: Action): PendingIntent = when (action) {
        Action.CONNECT -> PendingIntent.getForegroundService(
            context, REQUEST_CONNECT,
            Intent(context, ChainVpnService::class.java).setAction(ChainVpnService.ACTION_CONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Action.DISCONNECT -> PendingIntent.getService(
            context, REQUEST_DISCONNECT,
            Intent(context, ChainVpnService::class.java).setAction(ChainVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Action.OPEN_AND_CONNECT -> openApp(context, connect = true)
        Action.OPEN -> openApp(context, connect = false)
    }

    private fun openApp(context: Context, connect: Boolean): PendingIntent = PendingIntent.getActivity(
        context, if (connect) REQUEST_OPEN_AND_CONNECT else REQUEST_OPEN,
        ConnectRequest.openApp(context, connect),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stateText(state: WidgetState): Int = when (state.button) {
        Button.OFF -> R.string.widget_off
        Button.BUSY -> if (state.action == Action.OPEN) R.string.widget_stopping else R.string.widget_connecting
        Button.ON -> R.string.state_connected
        Button.HALF -> R.string.widget_openvpn_only
        Button.ERROR -> R.string.state_failed
        Button.LOCKED -> if (state.action == Action.OPEN) R.string.widget_set_up else R.string.widget_open_app
    }

    private fun actionText(state: WidgetState): Int = when (state.action) {
        Action.CONNECT -> R.string.connect
        Action.DISCONNECT -> if (state.button == Button.BUSY) R.string.cancel else R.string.disconnect
        Action.OPEN_AND_CONNECT, Action.OPEN -> R.string.widget_open_app
    }

    private fun miniDot(dot: Dot): Int = when (dot) {
        Dot.OK -> R.drawable.widget_mini_ok
        Dot.BUSY -> R.drawable.widget_mini_busy
        Dot.ERROR -> R.drawable.widget_mini_error
        Dot.IDLE, Dot.OFF -> R.drawable.widget_mini_idle
    }

    private fun line(whole: Boolean): Int = if (whole) R.drawable.widget_line_ok else R.drawable.widget_line_idle

    /**
     * From Android 12 the launcher resolves the colour itself, so it follows a
     * dark-theme switch without the app redrawing the widget.
     */
    private fun setTextColor(context: Context, views: RemoteViews, id: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= 31) views.setColorStateList(id, "setTextColor", color)
        else views.setTextColor(id, context.getColor(color))
    }
}
