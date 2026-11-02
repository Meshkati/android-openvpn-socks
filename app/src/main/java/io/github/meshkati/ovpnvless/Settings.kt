package io.github.meshkati.ovpnvless

import android.content.Context

/** What the user entered, kept in app-private SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var profile: String
        get() = prefs.getString("profile", "")!!
        set(v) = prefs.edit().putString("profile", v).apply()

    var username: String
        get() = prefs.getString("username", "")!!
        set(v) = prefs.edit().putString("username", v).apply()

    var password: String
        get() = prefs.getString("password", "")!!
        set(v) = prefs.edit().putString("password", v).apply()

    var vless: String
        get() = prefs.getString("vless", "")!!
        set(v) = prefs.edit().putString("vless", v).apply()

    /** The "Use VLESS" switch. Off means OpenVPN only, even with a VLESS setting saved. */
    var useVless: Boolean
        get() = prefs.getBoolean("use_vless", true)
        set(v) = prefs.edit().putBoolean("use_vless", v).apply()

    /**
     * The VLESS setting the user chose to keep despite its randomized TLS
     * fingerprint ([Fingerprint]). The home screen warns again once it changes.
     */
    var keptFingerprint: String
        get() = prefs.getString("kept_fingerprint", "")!!
        set(v) = prefs.edit().putString("kept_fingerprint", v).apply()

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
