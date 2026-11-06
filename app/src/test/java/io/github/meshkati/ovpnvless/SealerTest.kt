package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class SealerTest {

    // A software key stands in for the Keystore one.
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealer = Sealer { key }

    private val ovpn = "client\nremote vpn.example.com 1194\n<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\n…"

    @Test
    fun sealedTextOpensInTheSameField() {
        val sealed = sealer.seal("profile_1/ovpn", ovpn)
        assertTrue(Sealer.isSealed(sealed))
        assertEquals(ovpn, sealer.open("profile_1/ovpn", sealed))
    }

    @Test
    fun theSecretDoesNotShowInTheStoredText() {
        val sealed = sealer.seal("profile_1/password", "hunter2-hunter2")
        assertFalse(sealed.contains("hunter2"))
        assertFalse(String(Base64.getDecoder().decode(sealed.removePrefix(Sealer.PREFIX))).contains("hunter2"))
    }

    @Test
    fun everyWriteLooksDifferent() {
        // A random IV each time: equal passwords can't be spotted in the files.
        assertNotEquals(sealer.seal("profile_1/password", "same"), sealer.seal("profile_1/password", "same"))
    }

    @Test
    fun aValueMovedToAnotherFieldDoesNotOpen() {
        val sealed = sealer.seal("profile_1/password", "secret")
        assertNull(sealer.open("profile_1/username", sealed))
        assertNull(sealer.open("profile_2/password", sealed))
    }

    @Test
    fun aChangedValueDoesNotOpen() {
        val sealed = sealer.seal("profile_1/vless", "vless://uuid@host:443")
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(Sealer.PREFIX))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(sealer.open("profile_1/vless", Sealer.PREFIX + Base64.getEncoder().encodeToString(bytes)))
        assertNull(sealer.open("profile_1/vless", Sealer.PREFIX + "not base64!"))
        assertNull(sealer.open("profile_1/vless", Sealer.PREFIX + "AAAA"))
    }

    @Test
    fun anotherKeyDoesNotOpen() {
        // As after a device-to-device transfer: the Keystore key stayed on the old phone.
        val sealed = sealer.seal("profile_1/password", "secret")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(Sealer { otherKey }.open("profile_1/password", sealed))
    }

    @Test
    fun textSavedBeforeSealingReadsAsItIs() {
        assertEquals("old-password", sealer.open("profile_1/password", "old-password"))
        assertFalse(Sealer.isSealed("old-password"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", sealer.seal("profile_1/password", ""))
        assertEquals("", sealer.open("profile_1/password", ""))
    }

    @Test
    fun unicodeSurvives() {
        val text = "vless://id@host:443#سرور ۱ 🚀"
        assertEquals(text, sealer.open("profile_1/vless", sealer.seal("profile_1/vless", text)))
    }
}
