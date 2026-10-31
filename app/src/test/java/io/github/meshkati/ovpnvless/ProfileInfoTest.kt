package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileInfoTest {

    @Test
    fun blankProfileHasNoInfo() {
        assertNull(ProfileInfo.parse("  \n"))
    }

    @Test
    fun hostPortAndProtocolComeFromTheFirstRemote() {
        // given
        val profile = "client\nremote vpn.example.com 443 tcp\nremote backup.example.com 1194 udp\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals(ProfileInfo("vpn.example.com", 443, "TCP", null), info)
    }

    @Test
    fun portAndProtoLinesApplyWhenRemoteOmitsThem() {
        // given
        val profile = "proto tcp-client\nport 8443\nremote vpn.example.com\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals(ProfileInfo("vpn.example.com", 8443, "TCP", null), info)
    }

    @Test
    fun defaultsAreUdp1194() {
        // given
        val profile = "remote vpn.example.com\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals(ProfileInfo("vpn.example.com", 1194, "UDP", null), info)
    }

    @Test
    fun staticChallengePromptIsReadWithoutQuotes() {
        // given
        val profile = "remote vpn.example.com 443\nstatic-challenge \"Enter Authenticator Code\" 1\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals("Enter Authenticator Code", info.challenge)
    }

    @Test
    fun linesInsideInlineBlocksAreIgnored() {
        // given
        val profile = "<ca>\nremote not.a.host 1\n</ca>\nremote vpn.example.com 443\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals("vpn.example.com", info.host)
    }

    @Test
    fun remoteInsideConnectionBlockIsRead() {
        // given
        val profile = "<connection>\nremote vpn.example.com 443 tcp\n</connection>\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals("vpn.example.com", info.host)
    }

    @Test
    fun commentedOutRemoteIsIgnored() {
        // given
        val profile = "# remote old.example.com 1194\n;remote older.example.com\nremote vpn.example.com 443\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertEquals("vpn.example.com", info.host)
    }

    @Test
    fun profileWithoutRemoteHasNoHost() {
        // given
        val profile = "client\ndev tun\n"

        // when
        val info = ProfileInfo.parse(profile)!!

        // then
        assertNull(info.host)
    }
}
