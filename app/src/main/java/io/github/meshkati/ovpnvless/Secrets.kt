package io.github.meshkati.ovpnvless

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals text with AES-256-GCM under [key]: "sealed:v1:" and the base64 of
 * the 12-byte IV and the ciphertext. [label] (which field of which profile)
 * is authenticated too, so a sealed value only opens where it was written.
 */
class Sealer(private val key: () -> SecretKey) {

    /** Empty text stays empty, so "not set" still reads as empty without the key. */
    fun seal(label: String, plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks a random IV
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(sealed)
    }

    /**
     * The text [seal] was given. Text written before sealing comes back as it
     * is. Null when it can't be opened: the key is gone (the data was moved
     * to another phone, or the app's Keystore entries were cleared), or the
     * value was changed or moved to another field.
     */
    fun open(label: String, stored: String): String? {
        if (!isSealed(stored)) return stored
        return try {
            val bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            if (bytes.size < IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null // not base64
        }
    }

    companion object {
        const val PREFIX = "sealed:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        fun isSealed(stored: String) = stored.startsWith(PREFIX)
    }
}

/**
 * The profiles' secrets ([Profile]): the .ovpn, which can hold private keys
 * and tls-crypt keys, the sign-in and the VLESS link, which holds the UUID or
 * proxy password. They are sealed ([Sealer]) under a key that lives in the
 * Android Keystore, in secure hardware on most phones, and never leaves it.
 *
 * So the profile files alone don't give them away: a copy of the app's data
 * directory, or a device-to-device transfer (also excluded in
 * data_extraction_rules.xml). Root on the running phone can still use the key,
 * as the app does. The key needs no screen lock, so always-on VPN can start
 * at boot.
 */
object Secrets {
    private const val TAG = "Secrets"
    private const val ALIAS = "profile-secrets"
    private const val CACHE_SIZE = 64

    private val sealer = Sealer(::key)

    @Volatile
    private var cachedKey: SecretKey? = null

    // Opened values by their sealed text, which differs on every write (random
    // IV): the home screen reads the profile for every log line, and each
    // Keystore operation is a call to another process.
    private val opened = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > CACHE_SIZE
    }

    /**
     * Sealed text to store. If the Keystore fails, [plain] itself, as before
     * sealing: a setting the user typed is not dropped. The next write tries again.
     */
    fun seal(label: String, plain: String): String = try {
        sealer.seal(label, plain).also { if (it.isNotEmpty()) synchronized(opened) { opened[it] = plain } }
    } catch (e: Exception) {
        // A broken Keystore throws all sorts: KeyStoreException, IOException, ProviderException.
        Log.w(TAG, "can't seal $label, storing it unsealed: $e")
        plain
    }

    /** What [seal] was given; empty if it can't be opened any more ([Sealer.open]). */
    fun open(label: String, stored: String): String {
        if (!Sealer.isSealed(stored)) return stored
        synchronized(opened) { opened[stored] }?.let { return it }
        val plain = try {
            sealer.open(label, stored)
        } catch (e: Exception) {
            null // the Keystore itself failed (see seal)
        }
        if (plain == null) {
            Log.w(TAG, "can't open $label; it reads as empty")
            return ""
        }
        synchronized(opened) { opened[stored] = plain }
        return plain
    }

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) as SecretKey? ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
        cachedKey = key
        return key
    }
}
