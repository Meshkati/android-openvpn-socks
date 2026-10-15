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
}
