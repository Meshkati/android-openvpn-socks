package io.github.meshkati.ovpnvless

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.format.DateUtils
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Home: the connect toggle, the status, the route and, if needed, the
 * authenticator code. The route's VLESS step has a switch: off, or with no
 * VLESS server saved, the app connects with OpenVPN only. A card checks the
 * public IP ([IpCheck]), the way apps' traffic goes now, and shows its log.
 * Below, a notice when GitHub has a newer release.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_VPN = 1

        /** From a widget: connect as if the toggle was tapped (asks for the VPN permission or the code). */
        const val EXTRA_CONNECT = "connect"
    }

    private lateinit var settings: Settings
    private lateinit var toggle: PowerToggle
    private lateinit var state: TextView
    private lateinit var hint: TextView
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private lateinit var errorRaw: TextView
    private lateinit var routeCaption: TextView
    private lateinit var hopOpenVpn: HopView
    private lateinit var hopVless: HopView
    private lateinit var otpBox: View
    private lateinit var otp: EditText
    private lateinit var otpHelp: TextView
    private lateinit var infoBox: View
    private lateinit var infoText: TextView
    private lateinit var ipValue: TextView
    private lateinit var ipMeta: TextView
    private lateinit var checkIp: Button
    private lateinit var ipLog: TextView
    private lateinit var updateBox: View
    private lateinit var updateText: TextView
    private lateinit var crashBox: View
    private lateinit var crashText: TextView
    private lateinit var fpBox: View
    private lateinit var fpText: TextView

    private var profile: ProfileInfo? = null
    private var vless: VlessConfig? = null
    private var username = ""
    private var useVless = true
    private var stage = Stage.NOT_SET_UP
    // How Check IP would go now; null while connecting or disconnecting.
    private var ipVia: IpCheck.Via? = null
    private var update: Version? = null
    private val observer = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // A widget's request to connect is handled once, not again after a
        // rotation or when the app is reopened from the recent apps.
        if (savedInstanceState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            intent.removeExtra(EXTRA_CONNECT)
        }
        edgeToEdge(findViewById(R.id.root))
        settings = Settings(this)

        toggle = findViewById(R.id.toggle)
        state = findViewById(R.id.state)
        hint = findViewById(R.id.hint)
        errorBox = findViewById(R.id.error_box)
        errorText = findViewById(R.id.error_text)
        errorRaw = findViewById(R.id.error_raw)
        routeCaption = findViewById(R.id.route_caption)
        hopOpenVpn = findViewById(R.id.hop_openvpn)
        hopVless = findViewById(R.id.hop_vless)
        otpBox = findViewById(R.id.otp_box)
        otp = findViewById(R.id.otp)
        otpHelp = findViewById(R.id.otp_help)
        infoBox = findViewById(R.id.info_box)
        infoText = findViewById(R.id.info_text)
        ipValue = findViewById(R.id.ip_value)
        ipMeta = findViewById(R.id.ip_meta)
        checkIp = findViewById(R.id.check_ip)
        ipLog = findViewById(R.id.ip_log)
        updateBox = findViewById(R.id.update_box)
        updateText = findViewById(R.id.update_text)
        crashBox = findViewById(R.id.crash_box)
        crashText = findViewById(R.id.crash_text)
        fpBox = findViewById(R.id.fp_box)
        fpText = findViewById(R.id.fp_text)

        toggle.setOnClickListener { onToggle() }
        checkIp.setOnClickListener { ipVia?.let(IpCheck::start) }
        hopOpenVpn.setOnClickListener { startActivity(Intent(this, OpenVpnActivity::class.java)) }
        hopVless.setOnClickListener { startActivity(Intent(this, VlessActivity::class.java)) }
        hopVless.switch.contentDescription = getString(R.string.use_vless)
        hopVless.switch.setOnCheckedChangeListener { _, checked ->
            if (checked != useVless) {
                useVless = checked
                settings.useVless = checked
                render()
            }
        }
        val openLog = View.OnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<View>(R.id.open_log).setOnClickListener(openLog)
        findViewById<View>(R.id.view_log).setOnClickListener(openLog)
        findViewById<View>(R.id.open_donate).setOnClickListener {
            startActivity(Intent(this, DonateActivity::class.java))
        }
        findViewById<View>(R.id.open_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.update_download).setOnClickListener { update?.let(::openReleasePage) }
        findViewById<View>(R.id.update_later).setOnClickListener {
            update?.let { settings.dismissedRelease = "v$it" }
            showUpdate()
        }
        findViewById<View>(R.id.crash_report).setOnClickListener { sendCrash(share = false) }
        findViewById<View>(R.id.crash_share).setOnClickListener { sendCrash(share = true) }
        findViewById<View>(R.id.crash_dismiss).setOnClickListener {
            Crashes.clear(this)
            showCrash()
        }

        findViewById<View>(R.id.fp_fix).setOnClickListener { useSafeFingerprint() }
        findViewById<View>(R.id.fp_keep).setOnClickListener {
            settings.keptFingerprint = settings.vless
            render()
        }

        otp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onToggle()
                true
            } else {
                false
            }
        }
        otp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = showOtpError(false)
        })
    }

    override fun onStart() {
        super.onStart()
        // The setup screens may have changed these.
        profile = ProfileInfo.parse(settings.profile)
        vless = VlessConfig.parse(settings.vless)
        username = settings.username
        useVless = settings.useVless
        VpnState.addObserver(observer)
        showCrash()
        showUpdate()
        checkForUpdate()
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            intent.removeExtra(EXTRA_CONNECT) // not again when coming back to this screen
            connectFromWidget()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // onStart, which follows, reads it
    }

    override fun onStop() {
        VpnState.removeObserver(observer)
        // The setup screens and the VLESS switch may have changed what the widgets show.
        Widgets.refresh(this)
        super.onStop()
    }

    /**
     * A widget's button opens this screen when it can't connect by itself.
     * With a code to enter, the code field gets the focus; otherwise this
     * connects, asking for the VPN permission first if needed.
     */
    private fun connectFromWidget() {
        render()
        if (stage != Stage.READY && stage != Stage.FAILED) return
        if (profile?.challenge != null && otp.text.isBlank()) {
            otp.requestFocus()
            otp.post { getSystemService(InputMethodManager::class.java).showSoftInput(otp, 0) }
        } else {
            connect()
        }
    }

    private fun onToggle() {
        when (stage) {
            Stage.READY, Stage.FAILED -> connect()
            Stage.CONNECTING, Stage.CONNECTED -> disconnect()
            Stage.NOT_SET_UP -> Toast.makeText(this, R.string.profile_needed, Toast.LENGTH_SHORT).show()
            Stage.DISCONNECTING -> {}
        }
    }

    private fun connect() {
        if (profile?.challenge != null && otp.text.isBlank()) {
            showOtpError(true)
            otp.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(otp, 0)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        startForegroundService(
            Intent(this, ChainVpnService::class.java)
                .setAction(ChainVpnService.ACTION_CONNECT)
                .putExtra(ChainVpnService.EXTRA_OTP, otp.text.toString().trim())
        )
        otp.text.clear() // a code works only once
        otp.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(otp.windowToken, 0)
    }

    private fun disconnect() {
        startService(Intent(this, ChainVpnService::class.java).setAction(ChainVpnService.ACTION_DISCONNECT))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) startVpnService()
    }

    /** Asks GitHub for the latest release, at most once a day (see [Updates]). */
    private fun checkForUpdate() {
        val installed = BuildConfig.VERSION_NAME
        if (!Updates.checks(installed)) return
        if (!Updates.isDue(System.currentTimeMillis(), settings.nextUpdateCheck)) return
        // Counts as a failed try until it works, so returning to this screen or
        // rotating it does not start a second request.
        settings.nextUpdateCheck = System.currentTimeMillis() + Updates.INTERVAL_FAILED
        Thread({
            val tag = fetchLatestRelease("ovpnvless/$installed (Android)") ?: return@Thread
            runOnUiThread {
                settings.latestRelease = tag
                settings.nextUpdateCheck = System.currentTimeMillis() + Updates.INTERVAL_OK
                if (!isDestroyed) showUpdate()
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    private fun showUpdate() {
        val offer = Updates.offer(BuildConfig.VERSION_NAME, settings.latestRelease, settings.dismissedRelease)
        update = offer
        updateBox.visibility = if (offer != null) View.VISIBLE else View.GONE
        if (offer != null) updateText.text = getString(R.string.update_available, offer.toString())
    }

    private fun openReleasePage(version: Version) {
        val url = Updates.releasePage(version)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    /** The crash from an earlier run, if there is one ([Crashes]). */
    private fun showCrash() {
        val crash = Crashes.pending(this)
        crashBox.visibility = if (crash != null) View.VISIBLE else View.GONE
        if (crash != null) {
            val time = DateUtils.formatDateTime(this, crash.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            crashText.text = getString(R.string.crash_help, crash.kind.label, time)
        }
    }

    /**
     * Opens the report in a new GitHub issue, or in the share sheet. The user
     * sees and sends it there; the app sends nothing itself.
     */
    private fun sendCrash(share: Boolean) {
        val crash = Crashes.pending(this) ?: return showCrash()
        val app = BuildConfig.VERSION_NAME
        val device = CrashReport.Device(
            app = app,
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            route = getString(
                if (Route.choose(vless, useVless) == Route.VIA_VLESS) R.string.crash_route_vless
                else R.string.crash_route_openvpn
            ),
        )
        val secrets = CrashReport.secrets(settings.profile, settings.vless, settings.username)
        val title = CrashReport.title(crash, app)
        val body = CrashReport.body(crash, device, secrets)
        val intent = if (share) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, body),
                getString(R.string.crash_share),
            )
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(CrashReport.issueUrl(title, body)))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_no_browser, CrashReport.ISSUE_URL), Toast.LENGTH_LONG).show()
            return
        }
        Crashes.clear(this)
        showCrash()
    }

    /** Rewrites the saved link's fp= ([Fingerprint]). A running session keeps the old one until reconnected. */
    private fun useSafeFingerprint() {
        settings.vless = Fingerprint.fix(settings.vless)
        vless = VlessConfig.parse(settings.vless)
        val running = VpnState.phase != VpnState.Phase.DISCONNECTED
        Toast.makeText(this, if (running) R.string.fp_switched_reconnect else R.string.fp_switched,
            Toast.LENGTH_LONG).show()
        render()
    }

    private fun showOtpError(show: Boolean) {
        otp.setBackgroundResource(if (show) R.drawable.bg_field_error else R.drawable.bg_field)
        otpHelp.setText(if (show) R.string.otp_missing else R.string.otp_help)
        otpHelp.setTextColor(getColor(if (show) R.color.err else R.color.muted))
    }

    private fun render() {
        val profile = profile
        val vless = vless
        val phase = VpnState.phase
        val detail = VpnState.detail
        stage = Status.stage(phase, detail, ready = profile != null)
        val failure = if (stage == Stage.FAILED) Status.failure(detail, VpnState.vlessError) else null
        // While a session runs, show the route it was started with.
        val route = if (phase == VpnState.Phase.DISCONNECTED) Route.choose(vless, useVless) else VpnState.route

        // Toggle, title and hint
        toggle.mode = when (stage) {
            Stage.CONNECTING, Stage.DISCONNECTING -> PowerToggle.Mode.BUSY
            Stage.CONNECTED -> PowerToggle.Mode.ON
            else -> PowerToggle.Mode.OFF
        }
        toggle.isEnabled = stage != Stage.NOT_SET_UP && stage != Stage.DISCONNECTING
        toggle.contentDescription = getString(
            when (stage) {
                Stage.CONNECTING -> R.string.cancel
                Stage.CONNECTED, Stage.DISCONNECTING -> R.string.disconnect
                else -> R.string.connect
            }
        )
        state.setText(
            when (stage) {
                Stage.NOT_SET_UP -> R.string.state_not_set_up
                Stage.READY -> R.string.state_disconnected
                Stage.CONNECTING -> R.string.state_connecting
                Stage.CONNECTED -> R.string.state_connected
                Stage.DISCONNECTING -> R.string.state_disconnecting
                Stage.FAILED -> R.string.state_failed
            }
        )
        state.setTextColor(getColor(if (stage == Stage.FAILED) R.color.err else R.color.ink))
        hint.text = when (stage) {
            Stage.NOT_SET_UP -> getString(R.string.hint_not_set_up)
            Stage.READY -> getString(R.string.hint_connect)
            Stage.CONNECTING -> getString(R.string.hint_step_cancel, getString(stepText(Status.step(detail))))
            Stage.CONNECTED -> getString(R.string.hint_disconnect)
            Stage.DISCONNECTING -> ""
            Stage.FAILED -> getString(R.string.hint_retry)
        }
        hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE

        // Error notice
        errorBox.visibility = if (failure != null) View.VISIBLE else View.GONE
        if (failure != null) {
            errorText.setText(failureText(failure, profile?.challenge != null))
            errorRaw.text = if (failure == Failure.VLESS) VpnState.vlessError else detail
        }

        // Route
        routeCaption.setText(
            if (route == Route.VIA_VLESS) R.string.route_caption_vless else R.string.route_caption_openvpn
        )
        bindOpenVpn(profile, failure)
        bindVless(vless, failure, route)

        // Randomized TLS fingerprint in the VLESS link, unless the user kept it
        val fp = if (route == Route.VIA_VLESS && settings.vless != settings.keptFingerprint) {
            Fingerprint.risky(settings.vless)
        } else {
            null
        }
        fpBox.visibility = if (fp != null) View.VISIBLE else View.GONE
        if (fp != null) fpText.text = getString(R.string.fp_warning_help, fp)

        // Authenticator code and connected note
        otpBox.visibility =
            if (profile?.challenge != null && (stage == Stage.READY || stage == Stage.FAILED)) View.VISIBLE else View.GONE
        infoBox.visibility = if (stage == Stage.CONNECTED) View.VISIBLE else View.GONE
        infoText.setText(
            if (route == Route.VIA_VLESS) R.string.connected_info_vless else R.string.connected_info_openvpn
        )

        // Public IP check: without the VPN, through OpenVPN or through VLESS,
        // whichever way apps' traffic goes now.
        ipVia = IpCheck.Via.of(stage, route)
        bindIpCheck()
    }

    private fun bindIpCheck() {
        val check = IpCheck.state
        val via = if (check == IpCheck.State.IDLE) ipVia else IpCheck.via
        setTextKeepingSelection(ipValue, when (check) {
            IpCheck.State.IDLE -> getString(R.string.ip_not_checked)
            IpCheck.State.RUNNING -> getString(R.string.ip_checking)
            IpCheck.State.DONE -> IpCheck.result
            IpCheck.State.FAILED -> getString(R.string.ip_check_failed)
        })
        ipValue.setTextColor(getColor(when (check) {
            IpCheck.State.DONE -> R.color.ink
            IpCheck.State.FAILED -> R.color.err
            else -> R.color.muted
        }))
        ipMeta.text = when (check) {
            IpCheck.State.IDLE -> getString(when (via) {
                IpCheck.Via.VLESS -> R.string.ip_check_help_vless
                IpCheck.Via.OPENVPN -> R.string.ip_check_help_openvpn
                IpCheck.Via.DIRECT -> R.string.ip_check_help_direct
                null -> R.string.ip_check_help_wait
            })
            IpCheck.State.RUNNING -> getString(when (via) {
                IpCheck.Via.VLESS -> R.string.ip_check_running_vless
                IpCheck.Via.OPENVPN -> R.string.ip_check_running_openvpn
                else -> R.string.ip_check_running_direct
            })
            IpCheck.State.DONE -> getString(when (via) {
                IpCheck.Via.VLESS -> R.string.ip_check_done_vless
                IpCheck.Via.OPENVPN -> R.string.ip_check_done_openvpn
                else -> R.string.ip_check_done_direct
            }, IpCheck.millis / 1000.0)
            IpCheck.State.FAILED -> IpCheck.result
        }
        checkIp.isEnabled = check != IpCheck.State.RUNNING && ipVia != null
        checkIp.alpha = if (checkIp.isEnabled) 1f else 0.45f
        checkIp.setText(if (check == IpCheck.State.IDLE) R.string.check_ip else R.string.check_ip_again)

        val lines = IpCheck.lines()
        ipLog.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        val text = lines.joinToString("\n")
        if (text != ipLog.text.toString() && !ipLog.hasSelection()) ipLog.text = coloredLog(this, lines)
    }

    /** render() runs for every log line; replacing equal text would drop a selection. */
    private fun setTextKeepingSelection(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun bindOpenVpn(profile: ProfileInfo?, failure: Failure?) {
        val label = getString(R.string.hop_openvpn)
        if (profile == null) {
            hopOpenVpn.bind(1, getString(R.string.hop_step, 1), getString(R.string.add_openvpn),
                getString(R.string.add_openvpn_meta), HopView.State.ADD)
            return
        }
        val value = profile.host ?: getString(R.string.profile_no_server)
        val summary = openVpnSummary(this, profile, username)
        when (stage) {
            Stage.CONNECTING -> {
                val reconnecting = Status.step(VpnState.detail) == Step.RECONNECTING
                hopOpenVpn.bind(1, label, value,
                    getString(if (reconnecting) R.string.hop_reconnecting else R.string.hop_connecting), HopView.State.BUSY)
            }
            Stage.CONNECTED -> {
                val address = VpnState.tunnelAddress
                val meta = if (address.isEmpty()) getString(R.string.hop_connected)
                else getString(R.string.hop_connected_address, address)
                hopOpenVpn.bind(1, label, value, meta, HopView.State.OK)
            }
            Stage.FAILED -> if (failure == Failure.VLESS) {
                hopOpenVpn.bind(1, label, value, getString(R.string.hop_stopped), HopView.State.IDLE)
            } else {
                hopOpenVpn.bind(1, label, value, getString(failureShort(failure)), HopView.State.ERROR)
            }
            else -> hopOpenVpn.bind(1, label, value, summary, HopView.State.IDLE)
        }
    }

    private fun bindVless(vless: VlessConfig?, failure: Failure?, route: Route) {
        val label = getString(R.string.hop_vless)
        // The switch is for a saved VLESS setting, and only between sessions.
        hopVless.showSwitch(vless != null)
        hopVless.switch.isChecked = useVless
        hopVless.switch.isEnabled = stage == Stage.NOT_SET_UP || stage == Stage.READY || stage == Stage.FAILED
        if (vless == null) {
            hopVless.bind(2, getString(R.string.add_vless_label), getString(R.string.add_vless),
                getString(R.string.add_vless_meta), HopView.State.ADD)
            return
        }
        val value = vlessName(this, vless)
        if (route == Route.OPENVPN_ONLY) {
            hopVless.bind(2, label, value, getString(R.string.hop_vless_off), HopView.State.OFF)
            return
        }
        val summary = vlessSummary(this, vless)
        when (stage) {
            Stage.CONNECTING -> hopVless.bind(2, label, value, getString(R.string.hop_waiting), HopView.State.IDLE)
            Stage.CONNECTED -> hopVless.bind(2, label, value, getString(R.string.hop_connected_all_apps), HopView.State.OK)
            Stage.FAILED -> if (failure == Failure.VLESS) {
                hopVless.bind(2, label, value, getString(R.string.hop_vless_failed), HopView.State.ERROR)
            } else {
                hopVless.bind(2, label, value, getString(R.string.hop_not_started), HopView.State.IDLE)
            }
            else -> hopVless.bind(2, label, value, summary,
                if (vless == VlessConfig.Invalid) HopView.State.ERROR else HopView.State.IDLE)
        }
    }

    private fun stepText(step: Step) = when (step) {
        Step.STARTING -> R.string.step_starting
        Step.FINDING_SERVER -> R.string.step_finding_server
        Step.CONNECTING -> R.string.step_connecting
        Step.GETTING_SETTINGS -> R.string.step_getting_settings
        Step.SETTING_UP -> R.string.step_setting_up
        Step.RECONNECTING -> R.string.step_reconnecting
    }

    private fun failureText(failure: Failure, hasChallenge: Boolean) = when (failure) {
        Failure.PROFILE -> R.string.failure_profile
        Failure.CODE_NEEDED -> R.string.failure_code_needed
        Failure.CREDENTIALS -> R.string.failure_credentials
        Failure.SIGN_IN -> if (hasChallenge) R.string.failure_sign_in_code else R.string.failure_sign_in
        Failure.UNREACHABLE -> R.string.failure_unreachable
        Failure.CERTIFICATE -> R.string.failure_certificate
        Failure.VLESS -> R.string.failure_vless
        Failure.OTHER -> R.string.failure_other
    }
}

/** A short reason for the OpenVPN step, e.g. "Sign-in failed". */
fun failureShort(failure: Failure?) = when (failure) {
    Failure.PROFILE -> R.string.hop_profile_problem
    Failure.CODE_NEEDED -> R.string.hop_code_needed
    Failure.CREDENTIALS -> R.string.hop_sign_in_needed
    Failure.SIGN_IN -> R.string.hop_sign_in_failed
    Failure.UNREACHABLE -> R.string.hop_unreachable
    Failure.CERTIFICATE -> R.string.hop_certificate
    else -> R.string.hop_stopped
}

/** "TCP 443 · alex": protocol, port and, if set, the username. */
fun openVpnSummary(context: Context, profile: ProfileInfo, username: String): String = buildString {
    append(context.getString(R.string.profile_meta, profile.protocol, profile.port))
    if (username.isNotBlank()) append(" · ").append(username.trim())
}

/** The VLESS server's host, or what kind of setting it is. */
fun vlessName(context: Context, vless: VlessConfig): String = when (vless) {
    is VlessConfig.Link -> vless.host
    VlessConfig.Json -> context.getString(R.string.vless_custom)
    VlessConfig.Invalid -> context.getString(R.string.vless_link_name)
}

/** "443 · WebSocket · TLS". */
fun vlessSummary(context: Context, vless: VlessConfig): String = when (vless) {
    is VlessConfig.Link -> listOfNotNull(
        vless.port.toString(), vless.transport, vless.security.takeIf { it != "None" }, vless.flow,
    ).joinToString(" · ")
    VlessConfig.Json -> context.getString(R.string.vless_custom_meta)
    VlessConfig.Invalid -> context.getString(R.string.vless_invalid_meta)
}
