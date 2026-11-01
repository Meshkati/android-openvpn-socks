package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteTest {

    private val link = VlessConfig.parse("vless://id@edge.example.net:443?security=tls&type=ws#edge")

    @Test
    fun noVlessSettingIsOpenVpnOnly() {
        assertEquals(Route.OPENVPN_ONLY, Route.choose(vless = null, useVless = true))
    }

    @Test
    fun vlessSwitchedOffIsOpenVpnOnlyEvenWithALink() {
        assertEquals(Route.OPENVPN_ONLY, Route.choose(vless = link, useVless = false))
    }

    @Test
    fun vlessSwitchedOnWithALinkGoesViaVless() {
        assertEquals(Route.VIA_VLESS, Route.choose(vless = link, useVless = true))
    }

    @Test
    fun anUnreadableVlessSettingSwitchedOnStillGoesViaVless() {
        assertEquals(Route.VIA_VLESS, Route.choose(vless = VlessConfig.Invalid, useVless = true))
    }

    @Test
    fun ipv4CidrSplitsIntoAddressAndPrefix() {
        assertEquals(Cidr("10.8.0.6", 24), Cidr.parse("10.8.0.6/24"))
    }

    @Test
    fun ipv6DefaultRouteSplitsIntoAddressAndPrefix() {
        assertEquals(Cidr("::", 0), Cidr.parse("::/0"))
    }

    @Test
    fun addressWithoutPrefixIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Cidr.parse("10.8.0.6") }
    }
}
