package io.github.meshkati.ovpnvless

import io.github.meshkati.ovpnvless.IpCheck.Via
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IpCheckTest {

    @Test
    fun withTheVpnOffTheCheckGoesDirect() {
        for (stage in listOf(Stage.NOT_SET_UP, Stage.READY, Stage.FAILED)) {
            for (route in Route.entries) {
                assertEquals("$stage $route", Via.DIRECT, Via.of(stage, route))
            }
        }
    }

    @Test
    fun connectedTheCheckTakesTheSessionsRoute() {
        assertEquals(Via.VLESS, Via.of(Stage.CONNECTED, Route.VIA_VLESS))
        assertEquals(Via.OPENVPN, Via.of(Stage.CONNECTED, Route.OPENVPN_ONLY))
    }

    @Test
    fun noCheckWhileConnectingOrDisconnecting() {
        for (stage in listOf(Stage.CONNECTING, Stage.DISCONNECTING)) {
            for (route in Route.entries) {
                assertNull("$stage $route", Via.of(stage, route))
            }
        }
    }

    @Test
    fun theAnswerIsTheAddress() {
        assertEquals("203.0.113.7", IpCheck.answer(200, "203.0.113.7\n"))
        assertEquals("2001:db8::1", IpCheck.answer(200, " 2001:db8::1 "))
    }

    @Test
    fun anythingElseIsAnError() {
        val notOk = assertThrows(IOException::class.java) { IpCheck.answer(403, "203.0.113.7") }
        assertEquals("ifconfig.me answered HTTP 403", notOk.message)

        val page = assertThrows(IOException::class.java) { IpCheck.answer(200, "<html>captive portal</html>") }
        assertTrue(page.message!!.startsWith("the answer is not an IP address: <html>"))

        val empty = assertThrows(IOException::class.java) { IpCheck.answer(200, "") }
        assertEquals("the answer is not an IP address: (empty)", empty.message)
    }

    @Test
    fun ipAddressesAreRecognisedWithoutALookup() {
        val good = listOf(
            "0.0.0.0", "203.0.113.7", "255.255.255.255",
            "::", "::1", "2001:db8::1", "fe80::", "2001:db8:0:0:0:0:0:1", "::ffff:192.0.2.1",
        )
        val bad = listOf(
            "", "256.1.1.1", "1.2.3", "01.2.3.4", "example.com", "1.2.3.4 ",
            ":", ":::", "1::2::3", "2001:db8:0:0:0:0:0:0:1", "2001:db8:0:0:0:0:1", "12345::",
            "1.2.3.4::", "::g", ":1::2", "1::2:",
        )
        for (ip in good) assertTrue(ip, IpCheck.isIpAddress(ip))
        for (ip in bad) assertFalse(ip, IpCheck.isIpAddress(ip))
    }
}
