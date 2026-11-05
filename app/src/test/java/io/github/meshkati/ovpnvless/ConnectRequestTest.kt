package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ConnectRequestTest {

    private val key = ConnectRequest.newKey()

    @Test
    fun ownRequestConnects() {
        assertTrue(ConnectRequest.accepts(connect = true, given = key, expected = key))
    }

    @Test
    fun anotherAppsRequestDoesNot() {
        // MainActivity is exported: any app can send "connect" = true.
        assertFalse(ConnectRequest.accepts(connect = true, given = null, expected = key))
        assertFalse(ConnectRequest.accepts(connect = true, given = "guess", expected = key))
        assertFalse(ConnectRequest.accepts(connect = true, given = "", expected = ""))
    }

    @Test
    fun openingTheAppDoesNotConnect() {
        assertFalse(ConnectRequest.accepts(connect = false, given = key, expected = key))
    }

    @Test
    fun keysAreLongAndRandom() {
        assertEquals(43, key.length) // 32 bytes, base64url without padding
        assertNotEquals(key, ConnectRequest.newKey())
    }

    @Test
    fun readAtMostStopsAtTheLimit() {
        val data = ByteArray(10) { it.toByte() }
        assertEquals(4, readAtMost(ByteArrayInputStream(data), 4).size)
        assertEquals(10, readAtMost(ByteArrayInputStream(data), 64).size)
    }
}
