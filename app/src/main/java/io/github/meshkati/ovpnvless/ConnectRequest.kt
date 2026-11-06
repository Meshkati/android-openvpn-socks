package io.github.meshkati.ovpnvless

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The home screen connecting on its own, for a widget or the notification's
 * Resume ([MainActivity.EXTRA_CONNECT]). MainActivity is exported, as the
 * launcher needs, so any app can start it with that extra. Only intents from
 * this app's own PendingIntents carry the install's key ([Settings.connectKey]),
 * which other apps can't read, so only those connect.
 */
object ConnectRequest {
    const val EXTRA_KEY = "connect_key"

    /** The home screen, with or without the request to connect. */
    fun openApp(context: Context, connect: Boolean): Intent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONNECT, connect)
        if (connect) intent.putExtra(EXTRA_KEY, Settings(context).connectKey)
        return intent
    }

    /** Whether [intent] asks to connect and comes from this app. */
    fun isValid(context: Context, intent: Intent): Boolean = try {
        accepts(
            intent.getBooleanExtra(MainActivity.EXTRA_CONNECT, false),
            intent.getStringExtra(EXTRA_KEY),
            Settings(context).connectKey,
        )
    } catch (e: RuntimeException) {
        false // extras another app filled with something that doesn't unparcel
    }

    fun accepts(connect: Boolean, given: String?, expected: String): Boolean =
        connect && given != null && expected.isNotEmpty() &&
            MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

    fun newKey(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}
