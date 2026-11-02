package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutTest {

    @Test
    fun openVpnVersionFromCoreString() {
        assertEquals("3.11.7", About.openVpnVersion("OpenVPN 3 core release/3.11.7, android/arm64"))
        assertEquals("3.11.7", About.openVpnVersion("OpenVPN 3 core release/3.11.7"))
        assertEquals("abc123", About.openVpnVersion("OpenVPN 3 core abc123, android"))
    }

    @Test
    fun unexpectedCoreStringIsKept() {
        assertEquals("something else", About.openVpnVersion(" something else "))
        assertEquals("OpenVPN 3 core", About.openVpnVersion("OpenVPN 3 core"))
    }

    @Test
    fun copiedText() {
        assertEquals(
            "OpenVPN → VLESS 0.3.0\nXray-core 26.10.10\nOpenVPN 3 core 3.11.7",
            About.text(About.Versions("0.3.0", "26.10.10", "3.11.7"), "unknown"),
        )
        assertEquals(
            "OpenVPN → VLESS 0.1-debug\nXray-core unknown\nOpenVPN 3 core unknown",
            About.text(About.Versions("0.1-debug", null, null), "unknown"),
        )
    }
}
