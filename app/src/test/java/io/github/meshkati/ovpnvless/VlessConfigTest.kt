package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlessConfigTest {

    @Test
    fun blankTextHasNoConfig() {
        assertNull(VlessConfig.parse(" "))
    }

    @Test
    fun jsonIsACustomConfig() {
        assertEquals(VlessConfig.Json, VlessConfig.parse("{\"inbounds\": []}"))
    }

    @Test
    fun otherTextIsInvalid() {
        assertEquals(VlessConfig.Invalid, VlessConfig.parse("vmess://abc"))
    }

    @Test
    fun linkWithoutPortIsInvalid() {
        assertEquals(VlessConfig.Invalid, VlessConfig.parse("vless://uuid@edge.example.net"))
    }

    @Test
    fun realityVisionLinkIsSummarised() {
        // given
        val link = "vless://3f1c-e9a2@edge.example.net:443?type=tcp&security=reality&sni=www.microsoft.com" +
            "&pbk=abc&sid=01&fp=chrome&flow=xtls-rprx-vision#My%20server"

        // when
        val config = VlessConfig.parse(link)

        // then
        val expected = VlessConfig.Link(
            host = "edge.example.net", port = 443, transport = "TCP", path = null, security = "REALITY",
            flow = "Vision", sni = "www.microsoft.com", name = "My server",
        )
        assertEquals(expected, config)
    }

    @Test
    fun webSocketPathIsDecoded() {
        // given
        val link = "vless://id@edge.example.net:443?type=ws&security=tls&path=%2Fws%3Fed%3D2048"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("/ws?ed=2048", config.path)
    }

    @Test
    fun grpcShowsServiceName() {
        // given
        val link = "vless://id@edge.example.net:443?type=grpc&serviceName=tunnel&security=tls"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("tunnel", config.path)
    }

    @Test
    fun ipv6HostIsReadFromBrackets() {
        // given
        val link = "vless://id@[2001:db8::1]:8443?security=none"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("2001:db8::1", config.host)
    }

    @Test
    fun missingTypeMeansTcp() {
        // given
        val link = "vless://id@edge.example.net:80"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("TCP", config.transport)
    }

    @Test
    fun missingSecurityMeansNone() {
        // given
        val link = "vless://id@edge.example.net:80"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("None", config.security)
    }

    @Test
    fun socksLinkIsSummarised() {
        // given
        val link = "socks5://alex:p%40ss@proxy.example.net:1080#Office"

        // when
        val config = VlessConfig.parse(link)

        // then
        val expected = VlessConfig.Link(
            host = "proxy.example.net", port = 1080, transport = "TCP", path = null, security = "None",
            flow = null, sni = null, name = "Office", protocol = VlessConfig.SOCKS, user = "alex",
        )
        assertEquals(expected, config)
    }

    @Test
    fun v2rayNSocksLinkHasBase64Credentials() {
        // given: base64 of "alex:secret"
        val link = "socks://YWxleDpzZWNyZXQ=@proxy.example.net:1080"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("alex", config.user)
    }

    @Test
    fun proxyWithoutCredentials() {
        // given
        val link = "socks5h://[2001:db8::1]:1080"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals("2001:db8::1", config.host)
        assertNull(config.user)
    }

    @Test
    fun httpProxyLink() {
        // given
        val link = "HTTP://proxy.example.net:3128"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals(VlessConfig.HTTP, config.protocol)
        assertEquals("None", config.security)
    }

    @Test
    fun httpsProxyUsesTls() {
        // given
        val link = "https://u:p@proxy.example.net:443?sni=front.example.com"

        // when
        val config = VlessConfig.parse(link) as VlessConfig.Link

        // then
        assertEquals(VlessConfig.HTTP, config.protocol)
        assertEquals("TLS", config.security)
        assertEquals("front.example.com", config.sni)
        assertEquals("u", config.user)
    }

    @Test
    fun proxyWithoutPortIsInvalid() {
        assertEquals(VlessConfig.Invalid, VlessConfig.parse("socks5://proxy.example.net"))
    }

    @Test
    fun vlessLinkIsVless() {
        val config = VlessConfig.parse("vless://id@edge.example.net:80") as VlessConfig.Link
        assertEquals(VlessConfig.VLESS, config.protocol)
        assertNull(config.user)
    }
}
