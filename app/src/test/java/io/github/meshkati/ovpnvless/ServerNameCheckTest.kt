package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNameCheckTest {

    // As OpenVPN 3.11.8 with mbed TLS logged them, refusing a server certificate
    // without the TLS server usage (hosttest/run-device-tun.sh, modified).
    private val added = "profile doesn't check the server's certificate beyond its CA, so another client's " +
        "certificate could pose as the server; added remote-cert-tls server (use verify-x509-name for a " +
        "server certificate without the TLS server usage)"
    private val ca = """
        VERIFY OK : depth=1
        cert. version     : 3
        issuer name       : CN=e2e-ca
        subject name      : CN=e2e-ca
        basic constraints : CA=true
    """.trimIndent()
    private val leaf = """
        VERIFY OK : depth=0
        cert. version     : 3
        issuer name       : CN=e2e-ca
        subject name      : CN=e2e-server
        key usage         : Digital Signature
    """.trimIndent()
    private val ekuFailure = "VERIFY FAIL -- bad X509 extended key usage in leaf certificate"

    private fun track(vararg lines: String) = ServerNameCheck.Tracker().apply { lines.forEach(::onLog) }.name

    @Test
    fun theAddedCheckRefusingTheServerOffersItsName() {
        assertEquals("e2e-server", track(added, ca, leaf, ekuFailure))
        assertEquals("e2e-server", track(added, ca, leaf, "VERIFY FAIL -- bad X509 key usage in leaf certificate"))
    }

    @Test
    fun noOfferWhenTheProfileAskedForTheCheckItself() {
        // remote-cert-tls in the profile: the CVE fix refuses it, as OpenVPN 2.x does.
        assertNull(track(ca, leaf, ekuFailure))
    }

    @Test
    fun noOfferForOtherCertificateFailures() {
        assertNull(track(added, ca, leaf, "VERIFY FAIL -- verify-x509-name failed"))
        assertNull(track(added, ca, leaf)) // connected
    }

    @Test
    fun theLeafsNameNotTheCas() {
        assertEquals("vpn.example.com", ServerNameCheck.commonName(
            "VERIFY OK : depth=0\nsubject name      : C=US, O=Example Inc, CN=vpn.example.com\n"))
        assertNull(ServerNameCheck.commonName("VERIFY OK : depth=0\nsubject name      : O=Example\n"))
    }

    @Test
    fun namesThatCantBeWrittenPlainlyAreNotOffered() {
        for (cn in listOf("a\"b", "a\\b", "it's", "", " padded", "x".repeat(65))) {
            assertNull(cn, ServerNameCheck.commonName("subject name : CN=$cn"))
        }
        assertEquals("Home VPN", ServerNameCheck.commonName("subject name : CN=Home VPN"))
    }

    @Test
    fun applyAddsVerifyX509Name() {
        assertEquals("client\nremote a 1\nverify-x509-name e2e-server name\n",
            ServerNameCheck.apply("client\nremote a 1\n", "e2e-server"))
        assertEquals("client\nverify-x509-name \"Home VPN\" name\n",
            ServerNameCheck.apply("client\r\n", "Home VPN"))
    }

    @Test
    fun aProfileThatChecksByNameGetsNoOffer() {
        assertTrue(ServerNameCheck.hasNameCheck("client\n  verify-x509-name vpn name\n"))
        assertFalse(ServerNameCheck.hasNameCheck("client\n# verify-x509-name vpn name\n"))
    }
}
