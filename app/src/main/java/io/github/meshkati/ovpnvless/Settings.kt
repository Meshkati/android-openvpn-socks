package io.github.meshkati.ovpnvless

import android.content.Context

/**
 * App-wide state, kept in app-private SharedPreferences. What the user sets up
 * for a connection is in each [Profile]; this keeps their order and the one
 * in use.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private companion object {
        /** The one setup's keys before profiles. */
        val LEGACY_KEYS = listOf("profile", "username", "password", "vless", "use_vless", "kept_fingerprint")
    }

    /** The saved profiles' ids in order, e.g. "1,3,2" ([Profiles]). Empty until profiles are set up. */
    val profileOrder: String
        get() = prefs.getString("profiles", "")!!

    /** The id of the profile in use ([Profiles]). */
    val activeProfile: Long
        get() = prefs.getLong("active_profile", 0)

    /** The id the next new profile gets. Ids are never reused. */
    var nextProfileId: Long
        get() = prefs.getLong("next_profile_id", 1)
        set(v) {
            prefs.edit().putLong("next_profile_id", v).commit()
        }

    fun setProfiles(order: String, active: Long) {
        prefs.edit().putString("profiles", order).putLong("active_profile", active).commit()
    }

    /**
     * Before profiles, the one setup was kept here. Null when there is none,
     * as on a new install.
     */
    fun legacySetup(): Legacy? {
        if (LEGACY_KEYS.none { prefs.contains(it) }) return null
        return Legacy(
            ovpn = prefs.getString("profile", "")!!,
            username = prefs.getString("username", "")!!,
            password = prefs.getString("password", "")!!,
            vless = prefs.getString("vless", "")!!,
            useVless = prefs.getBoolean("use_vless", true),
            keptFingerprint = prefs.getString("kept_fingerprint", "")!!,
        )
    }

    /**
     * Saves the first profile list and, in the same write, removes the setup
     * [legacySetup] read, which the first profile now holds.
     */
    fun startProfiles(order: String, active: Long, nextId: Long) {
        val edit = prefs.edit()
            .putString("profiles", order)
            .putLong("active_profile", active)
            .putLong("next_profile_id", nextId)
        LEGACY_KEYS.forEach { edit.remove(it) }
        edit.commit()
    }

    class Legacy(
        val ovpn: String, val username: String, val password: String,
        val vless: String, val useVless: Boolean, val keptFingerprint: String,
    )

    /** The latest release tag GitHub reported, e.g. "v0.2.0". */
    var latestRelease: String
        get() = prefs.getString("latest_release", "")!!
        set(v) = prefs.edit().putString("latest_release", v).apply()

    /** The release the user tapped "Later" on: it is not shown again. */
    var dismissedRelease: String
        get() = prefs.getString("dismissed_release", "")!!
        set(v) = prefs.edit().putString("dismissed_release", v).apply()

    /** When the app may ask GitHub again, in milliseconds since the epoch. */
    var nextUpdateCheck: Long
        get() = prefs.getLong("next_update_check", 0)
        set(v) = prefs.edit().putLong("next_update_check", v).apply()

    /** Time of the newest process exit already looked at for a crash report ([Crashes]). */
    var lastExitSeen: Long
        get() = prefs.getLong("last_exit_seen", 0)
        set(v) = prefs.edit().putLong("last_exit_seen", v).apply()
}
