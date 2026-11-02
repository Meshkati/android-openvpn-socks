package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {

    private val tlsLink = "vless://id@edge.example.net:443?type=ws&security=tls&path=%2Fws&fp=randomized" +
        "&sni=cdn.example.net#My%20server"

    @Test
    fun randomizedOnTlsIsRisky() {
        assertEquals("randomized", Fingerprint.risky(tlsLink))
    }

    @Test
    fun randomizedNoAlpnInAnyCaseIsRisky() {
        // given
        val link = "vless://id@edge.example.net:443?security=TLS&fp=RandomizedNoALPN"

        // when
        val fp = Fingerprint.risky(link)

        // then
        assertEquals("RandomizedNoALPN", fp)
    }

    @Test
    fun fixedFingerprintsAreFine() {
        assertNull(Fingerprint.risky(tlsLink.replace("fp=randomized", "fp=chrome")))
        assertNull(Fingerprint.risky(tlsLink.replace("fp=randomized", "fp=random")))
        assertNull(Fingerprint.risky(tlsLink.replace("&fp=randomized", "")))
    }

    @Test
    fun onlyTlsLinksAreChecked() {
        assertNull(Fingerprint.risky(tlsLink.replace("security=tls", "security=reality")))
        assertNull(Fingerprint.risky(tlsLink.replace("security=tls", "security=none")))
    }

    @Test
    fun otherTextIsNotChecked() {
        assertNull(Fingerprint.risky("{\"outbounds\": [{\"fingerprint\": \"randomized\"}]}"))
        assertNull(Fingerprint.risky("vless://id@edge.example.net:443#fp=randomized"))
        assertNull(Fingerprint.risky(""))
    }

    @Test
    fun theFirstValueCountsLikeInTheEngine() {
        assertEquals("randomized", Fingerprint.risky("$tlsLink&fp=chrome".substringBefore('#')))
        assertNull(Fingerprint.risky("vless://id@h:443?security=tls&fp=chrome&fp=randomized"))
    }

    @Test
    fun fixSetsChromeAndKeepsTheRest() {
        // when
        val fixed = Fingerprint.fix(tlsLink)

        // then
        val expected = "vless://id@edge.example.net:443?type=ws&security=tls&path=%2Fws&fp=chrome" +
            "&sni=cdn.example.net#My%20server"
        assertEquals(expected, fixed)
        assertNull(Fingerprint.risky(fixed))
    }

    @Test
    fun fixReplacesEveryFp() {
        assertEquals(
            "vless://id@h:443?fp=chrome&security=tls&fp=chrome",
            Fingerprint.fix("  vless://id@h:443?fp=randomized&security=tls&fp=randomizednoalpn\n"),
        )
    }

    @Test
    fun fixLeavesOtherTextAlone() {
        assertEquals("{\"a\": 1}", Fingerprint.fix("{\"a\": 1}"))
    }
}
