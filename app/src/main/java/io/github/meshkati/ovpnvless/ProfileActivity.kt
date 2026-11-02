package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * One profile: its name and route. The route rows open the OpenVPN and VLESS
 * screens for this profile, so it can be set up without switching to it.
 * "Use this profile" switches to it ([switchProfile]) and goes back home.
 *
 * [EXTRA_NEW]: a profile just made, from the picker or the Profiles screen.
 * It has no Duplicate or Delete, and is dropped if it is left empty.
 */
class ProfileActivity : Activity() {

    companion object {
        const val EXTRA_NEW = "new"
    }

    private lateinit var profile: Profile
    private var isNew = false
    private lateinit var title: TextView
    private lateinit var name: EditText
    private lateinit var hopOpenVpn: HopView
    private lateinit var hopVless: HopView
    private lateinit var use: Button
    private lateinit var useHelp: TextView
    private lateinit var inUse: View
    private lateinit var delete: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        edgeToEdge(findViewById(R.id.root))
        profile = Profiles.of(this) ?: return finish() // deleted meanwhile
        isNew = intent.getBooleanExtra(EXTRA_NEW, false)

        title = findViewById(R.id.title)
        name = findViewById(R.id.profile_name)
        hopOpenVpn = findViewById(R.id.hop_openvpn)
        hopVless = findViewById(R.id.hop_vless)
        use = findViewById(R.id.use)
        useHelp = findViewById(R.id.use_help)
        inUse = findViewById(R.id.in_use)
        delete = findViewById(R.id.delete)

        name.setText(profile.name)
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderTitle()
        })

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        hopOpenVpn.setOnClickListener { openSetup(OpenVpnActivity::class.java) }
        hopVless.setOnClickListener { openSetup(VlessActivity::class.java) }
        hopVless.switch.contentDescription = getString(R.string.use_vless)
        hopVless.switch.setOnCheckedChangeListener { _, checked ->
            if (checked != profile.useVless) {
                profile.useVless = checked
                render()
            }
        }
        use.setOnClickListener {
            saveName()
            switchProfile(this, profile.id)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        findViewById<View>(R.id.duplicate).setOnClickListener {
            saveName()
            val copy = Profiles.create(this, copyOf = profile)
            startActivity(Intent(this, ProfileActivity::class.java).putExtra(Profiles.EXTRA_PROFILE, copy.id))
            finish()
        }
        delete.setOnClickListener {
            saveName()
            // The Profiles screen this came from offers Undo.
            if (Profiles.delete(this, profile.id) != null) finish()
        }
        findViewById<View>(R.id.duplicate).visibility = if (isNew) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        // The OpenVPN and VLESS screens may have changed it, or it may have been deleted.
        if (Profiles.get(this, profile.id) == null) return finish()
        render()
    }

    override fun onPause() {
        if (this::profile.isInitialized) {
            saveName()
            // A new profile nobody filled in is not kept.
            if (isNew && isFinishing && profile.isEmpty) Profiles.discard(this, profile.id)
        }
        super.onPause()
    }

    private fun saveName() {
        val typed = name.text.toString().trim()
        if (typed != profile.name) profile.name = typed
    }

    private fun openSetup(screen: Class<out Activity>) {
        saveName()
        startActivity(Intent(this, screen).putExtra(Profiles.EXTRA_PROFILE, profile.id))
    }

    private fun renderTitle() {
        val typed = name.text.toString().trim()
        title.text = when {
            isNew && typed.isEmpty() -> getString(R.string.profiles_new)
            else -> ProfileNames.display(typed, ProfileInfo.parse(profile.ovpn)?.host)
                ?: getString(R.string.profiles_unnamed)
        }
    }

    private fun render() {
        renderTitle()
        val info = ProfileInfo.parse(profile.ovpn)
        val vless = VlessConfig.parse(profile.vless)
        val active = profile.id == Profiles.activeId(this)
        val running = VpnState.phase != VpnState.Phase.DISCONNECTED

        // Unnamed, the name field shows the name it falls back to.
        name.hint = info?.host ?: getString(R.string.profiles_name_hint)

        if (info == null) {
            hopOpenVpn.bind(1, getString(R.string.hop_step, 1), getString(R.string.add_openvpn),
                getString(R.string.add_openvpn_meta), HopView.State.ADD)
        } else {
            hopOpenVpn.bind(1, getString(R.string.hop_openvpn), info.host ?: getString(R.string.profile_no_server),
                openVpnSummary(this, info, profile.username), HopView.State.IDLE)
        }

        // Like the home screen: the switch is for a saved VLESS setting, and
        // the profile a session is using keeps its route until it ends.
        hopVless.showSwitch(vless != null)
        hopVless.switch.isChecked = profile.useVless
        hopVless.switch.isEnabled = !(active && running)
        when {
            vless == null -> hopVless.bind(2, getString(R.string.add_vless_label), getString(R.string.add_vless),
                getString(R.string.add_vless_meta), HopView.State.ADD)
            !profile.useVless -> hopVless.bind(2, getString(R.string.hop_vless), vlessName(this, vless),
                getString(R.string.hop_vless_off), HopView.State.OFF)
            else -> hopVless.bind(2, getString(R.string.hop_vless), vlessName(this, vless), vlessSummary(this, vless),
                if (vless == VlessConfig.Invalid) HopView.State.ERROR else HopView.State.IDLE)
        }

        // "Use this profile", or that it is the one in use.
        use.visibility = if (active) View.GONE else View.VISIBLE
        inUse.visibility = if (active) View.VISIBLE else View.GONE
        use.isEnabled = info != null
        use.alpha = if (use.isEnabled) 1f else 0.45f
        useHelp.visibility = if (!active && info == null) View.VISIBLE else View.GONE

        // The only profile stays; the one a session is using can't go until it ends.
        delete.visibility = if (isNew || Profiles.ids(this).size < 2) View.GONE else View.VISIBLE
        delete.isEnabled = !(active && running)
        delete.alpha = if (delete.isEnabled) 1f else 0.45f
    }
}
