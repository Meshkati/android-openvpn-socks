package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLinesTest {

    @Test
    fun linesAreGroupedByServicePrefix() {
        // given
        val lines = mapOf(
            "openvpn: Contacting 203.0.113.7:443 via TCP" to LogLines.Source.OPENVPN,
            "openvpn event: CONNECTED alex@vpn" to LogLines.Source.OPENVPN,
            "OpenVPN tunnel: 10.8.0.6/24 dns=10.8.0.1 mtu=1500" to LogLines.Source.OPENVPN,
            "xray: [Info] proxy/tun: tun started" to LogLines.Source.XRAY,
            "failed to start VLESS: bad link" to LogLines.Source.APP,
        )

        // when
        val wrong = lines.filter { (line, source) -> LogLines.source(line) != source }

        // then
        assertEquals(emptyMap<String, LogLines.Source>(), wrong)
    }

    @Test
    fun failedLineIsAnError() {
        assertTrue(LogLines.isError("failed to start VLESS: bad link"))
    }

    @Test
    fun ordinaryLineIsNotAnError() {
        assertFalse(LogLines.isError("openvpn event: CONNECTED"))
    }
}
