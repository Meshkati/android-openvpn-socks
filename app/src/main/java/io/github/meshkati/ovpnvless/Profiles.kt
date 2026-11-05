package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import java.io.File

/**
 * One saved profile: a whole route under a name. The .ovpn with its sign-in
 * and, if wanted, a VLESS server. Kept in its own app-private
 * SharedPreferences file, "profile_<id>". The fields that hold secrets are
 * sealed with a Keystore key ([Secrets]); the name and the switch are not.
 */
class Profile(context: Context, val id: Long) {
    private val prefs = context.getSharedPreferences(fileName(id), Context.MODE_PRIVATE)

    /** What the user typed. Blank: the profile is named after its OpenVPN server ([displayName]). */
    var name: String
        get() = prefs.getString("name", "")!!
        set(v) = prefs.edit().putString("name", v).apply()

    /** The .ovpn text. */
    var ovpn: String
        get() = secret("ovpn")
        set(v) = setSecret("ovpn", v)

    var username: String
        get() = secret("username")
        set(v) = setSecret("username", v)

    var password: String
        get() = secret("password")
        set(v) = setSecret("password", v)

    /** The vless:// link or Xray JSON. */
    var vless: String
        get() = secret("vless")
        set(v) = setSecret("vless", v)

    /** The "Use VLESS" switch. Off means OpenVPN only, even with a VLESS setting saved. */
    var useVless: Boolean
        get() = prefs.getBoolean("use_vless", true)
        set(v) = prefs.edit().putBoolean("use_vless", v).apply()

    /**
     * The VLESS setting the user chose to keep despite its randomized TLS
     * fingerprint ([Fingerprint]). The home screen warns again once it changes.
     */
    var keptFingerprint: String
        get() = secret("kept_fingerprint") // a copy of the VLESS setting
        set(v) = setSecret("kept_fingerprint", v)

    /** Nothing entered yet: a new profile left like this is dropped ([Profiles.discard]). */
    val isEmpty: Boolean
        get() = name.isBlank() && ovpn.isBlank() && username.isBlank() && password.isEmpty() && vless.isBlank()

    /** The name, else the OpenVPN server, else "My profile". */
    fun displayName(context: Context): String =
        ProfileNames.display(name, ProfileInfo.parse(ovpn)?.host) ?: context.getString(R.string.profiles_unnamed)

    /** Writes everything at once, so a new profile is never half there. */
    internal fun write(
        name: String, ovpn: String, username: String, password: String,
        vless: String, useVless: Boolean, keptFingerprint: String,
    ) {
        prefs.edit().clear()
            .putString("name", name).putString("ovpn", seal("ovpn", ovpn))
            .putString("username", seal("username", username)).putString("password", seal("password", password))
            .putString("vless", seal("vless", vless)).putBoolean("use_vless", useVless)
            .putString("kept_fingerprint", seal("kept_fingerprint", keptFingerprint))
            .commit()
    }

    /** A sealed field. One saved before sealing is sealed now. */
    private fun secret(field: String): String {
        val stored = prefs.getString(field, "")!!
        if (stored.isNotEmpty() && !Sealer.isSealed(stored)) {
            prefs.edit().putString(field, seal(field, stored)).apply()
            return stored
        }
        return Secrets.open(label(field), stored)
    }

    private fun setSecret(field: String, value: String) {
        // The setup screens save on every pause; unchanged, there is nothing to seal.
        if (value == secret(field)) return
        prefs.edit().putString(field, seal(field, value)).apply()
    }

    private fun seal(field: String, value: String) = Secrets.seal(label(field), value)

    private fun label(field: String) = "${fileName(id)}/$field"

    internal fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        fun fileName(id: Long) = "profile_$id"
    }
}

/**
 * The saved profiles ([ProfileList] in [Settings]) and the one in use. There
 * is always at least one: the first start makes it, from the setup the app
 * kept before it had profiles, or empty on a new install.
 *
 * Main thread only.
 */
object Profiles {

    /** Activities that edit one profile take its id; without one they edit the profile in use. */
    const val EXTRA_PROFILE = "profile"

    /** A deleted profile, kept until [forget] so [undo] can bring it back. */
    class Deleted(val id: Long, val index: Int, val wasActive: Boolean, val name: String)

    /** The last delete, while it can still be undone. */
    var pending: Deleted? = null
        private set

    private var orphansChecked = false

    fun list(context: Context): ProfileList {
        val settings = Settings(context)
        if (settings.profileOrder.isEmpty()) start(context, settings)
        val list = ProfileList.parse(settings.profileOrder, settings.activeProfile)
        if (!orphansChecked) {
            orphansChecked = true
            removeOrphans(context, list)
        }
        return list
    }

    fun ids(context: Context): List<Long> = list(context).ids

    fun all(context: Context): List<Profile> = ids(context).map { Profile(context, it) }

    /** Null once the profile is deleted. */
    fun get(context: Context, id: Long): Profile? = if (id in ids(context)) Profile(context, id) else null

    fun activeId(context: Context): Long = list(context).active

    fun active(context: Context): Profile = Profile(context, activeId(context))

    fun setActive(context: Context, id: Long) = save(context, list(context).use(id))

    /** A new profile at the end of the list, or a copy of [copyOf] right after it. Not in use yet. */
    fun create(context: Context, copyOf: Profile? = null): Profile {
        val list = list(context)
        val settings = Settings(context)
        val id = maxOf(settings.nextProfileId, (list.ids.maxOrNull() ?: 0) + 1)
        settings.nextProfileId = id + 1
        val profile = Profile(context, id)
        if (copyOf == null) {
            profile.clear()
        } else {
            val names = all(context).map { it.displayName(context) }
            profile.write(
                name = ProfileNames.copy(copyOf.displayName(context), names),
                ovpn = copyOf.ovpn, username = copyOf.username, password = copyOf.password,
                vless = copyOf.vless, useVless = copyOf.useVless, keptFingerprint = copyOf.keptFingerprint,
            )
        }
        save(context, list.add(id, after = copyOf?.id))
        return profile
    }

    /**
     * Takes [id] out of the list; its data stays until [forget] (or the next
     * delete), so [undo] can put it back. The only profile can't be deleted.
     */
    fun delete(context: Context, id: Long): Deleted? {
        val list = list(context)
        val index = list.ids.indexOf(id)
        if (index < 0 || list.ids.size < 2) return null
        forget(context)
        val deleted = Deleted(id, index, list.active == id, Profile(context, id).displayName(context))
        save(context, list.remove(id))
        pending = deleted
        return deleted
    }

    fun undo(context: Context) {
        val deleted = pending ?: return
        pending = null
        save(context, list(context).restore(deleted.id, deleted.index, deleted.wasActive))
    }

    /** Deletes the [pending] profile's data for good. */
    fun forget(context: Context) {
        val deleted = pending ?: return
        pending = null
        erase(context, deleted.id)
    }

    /** Removes a profile at once, without undo: a new profile left empty. */
    fun discard(context: Context, id: Long) {
        val list = list(context)
        if (id !in list.ids || list.ids.size < 2) return
        save(context, list.remove(id))
        erase(context, id)
    }

    fun move(context: Context, id: Long, by: Int) = save(context, list(context).move(id, by))

    /** The profile an activity edits: [EXTRA_PROFILE], or the one in use. Null if it was deleted. */
    fun of(activity: Activity): Profile? {
        val id = activity.intent.getLongExtra(EXTRA_PROFILE, ProfileList.NONE)
        return if (id == ProfileList.NONE) active(activity) else get(activity, id)
    }

    /**
     * The first start with profiles: the setup kept before them becomes the
     * first profile, unnamed so it shows the OpenVPN server's name.
     */
    private fun start(context: Context, settings: Settings) {
        val id = 1L
        val legacy = settings.legacySetup()
        val profile = Profile(context, id)
        if (legacy == null) {
            profile.clear()
        } else {
            profile.write(
                name = "", ovpn = legacy.ovpn, username = legacy.username, password = legacy.password,
                vless = legacy.vless, useVless = legacy.useVless, keptFingerprint = legacy.keptFingerprint,
            )
        }
        settings.startProfiles(order = id.toString(), active = id, nextId = id + 1)
    }

    private fun save(context: Context, list: ProfileList) {
        Settings(context).setProfiles(list.format(), list.active)
    }

    private fun erase(context: Context, id: Long) {
        Profile(context, id).clear()
        context.deleteSharedPreferences(Profile.fileName(id))
    }

    /** Profile files no list entry points to, e.g. after the app was closed with an Undo pending. */
    private fun removeOrphans(context: Context, list: ProfileList) {
        val files = File(context.dataDir, "shared_prefs").listFiles() ?: return
        val name = Regex("""profile_(\d+)\.xml""")
        for (file in files) {
            val id = name.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (id !in list.ids && id != pending?.id) context.deleteSharedPreferences(Profile.fileName(id))
        }
    }
}

/**
 * Makes [id] the profile in use. A session that is connecting or connected
 * restarts with it; if it needs an authenticator code or isn't set up, the
 * session stops and the home screen asks for what's missing. One that is
 * already disconnecting just ends.
 */
fun switchProfile(context: Context, id: Long) {
    if (id == Profiles.activeId(context)) return
    Profiles.setActive(context, id)
    val phase = VpnState.phase
    if (phase == VpnState.Phase.CONNECTING || phase == VpnState.Phase.CONNECTED) {
        val info = ProfileInfo.parse(Profiles.active(context).ovpn)
        val service = Intent(context, ChainVpnService::class.java)
        if (info != null && info.challenge == null) {
            context.startForegroundService(service.setAction(ChainVpnService.ACTION_RECONNECT))
        } else {
            context.startService(service.setAction(ChainVpnService.ACTION_DISCONNECT))
        }
    }
    Widgets.refresh(context)
}

/** Shows [profile]'s name under a setup screen's title, once there is more than one profile. */
fun Activity.showProfileName(profile: Profile) {
    val subtitle = findViewById<TextView>(R.id.app_bar_subtitle) ?: return
    val show = Profiles.ids(this).size > 1
    subtitle.visibility = if (show) View.VISIBLE else View.GONE
    if (show) subtitle.text = profile.displayName(this)
}

/** "vpn.example.com → edge.example.net", "home.example.org · OpenVPN only", or "Not set up". */
fun profileRoute(context: Context, profile: Profile): String {
    val info = ProfileInfo.parse(profile.ovpn) ?: return context.getString(R.string.profiles_route_not_set_up)
    val host = info.host ?: context.getString(R.string.profile_no_server)
    val vless = VlessConfig.parse(profile.vless)
    return when {
        vless == null -> context.getString(R.string.profiles_route_openvpn_only, host)
        !profile.useVless -> context.getString(R.string.profiles_route_vless_off, host)
        else -> context.getString(R.string.notification_route, host, vlessName(context, vless))
    }
}
