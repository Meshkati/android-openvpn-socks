package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CrashReportTest {

    private val device = CrashReport.Device(
        app = "0.2.0",
        android = "16 (API 36), arm64-v8a",
        model = "Google Pixel 10 Pro",
        route = "OpenVPN → VLESS",
    )

    @Test
    fun pendingCrashRoundTrips() {
        val crash = Crash(Crash.Kind.GO, 1_780_000_000_000, "panic: boom\n\ngoroutine 1 [running]:")
        assertEquals(crash, Crash.decode(crash.encode()))
        assertNull(Crash.decode("NOPE\n1\nx"))
        assertNull(Crash.decode("GO\nlater\nx"))
        assertNull(Crash.decode(""))
    }

    @Test
    fun picksLatestButPrefersTheStackBehindANativeCrash() {
        val java = Crash(Crash.Kind.JAVA, 1_000, "java")
        val go = Crash(Crash.Kind.GO, 50_000, "go")
        val native = Crash(Crash.Kind.NATIVE, 52_000, "native")
        assertEquals(go, CrashReport.pick(listOf(java, go, native)))
        // A native crash long after the Go panic is a crash of its own.
        val laterNative = native.copy(time = 90_000)
        assertEquals(laterNative, CrashReport.pick(listOf(java, go, laterNative)))
        assertEquals(go, CrashReport.pick(listOf(java, go)))
        assertNull(CrashReport.pick(emptyList()))
    }

    @Test
    fun anrKeepsTheMainThread() {
        val traces = """
            ----- pid 123 at 2026-11-02 -----
            "Signal Catcher" daemon prio=10 tid=4 Runnable
              at x

            "main" prio=5 tid=1 Sleeping
              at java.lang.Thread.sleep(Native method)
              at io.github.meshkati.ovpnvless.MainActivity.render(MainActivity.kt:10)

            "openvpn3" prio=5 tid=20 Native
              at y
        """.trimIndent()
        assertEquals(
            "Input dispatching timed out\n\n" +
                "\"main\" prio=5 tid=1 Sleeping\n" +
                "  at java.lang.Thread.sleep(Native method)\n" +
                "  at io.github.meshkati.ovpnvless.MainActivity.render(MainActivity.kt:10)",
            CrashReport.anrDetails("Input dispatching timed out", traces),
        )
        assertEquals("No details", CrashReport.anrDetails(null, null))
    }

    @Test
    fun secretsComeFromTheSettings() {
        val profile = """
            client
            remote vpn.example.com 1194 udp
            <connection>
            remote backup.example.net 443 tcp
            </connection>
        """.trimIndent()
        val vless = "vless://0f8e2a3c-1111-4222-8333-123456789abc@edge.example.org:443?security=tls#me"
        val secrets = CrashReport.secrets(profile, vless, "alex.smith")
        assertEquals(
            setOf("vpn.example.com", "backup.example.net", "edge.example.org",
                "0f8e2a3c-1111-4222-8333-123456789abc", "alex.smith"),
            secrets.toSet(),
        )
        // Too short to hide without mangling ordinary words.
        assertFalse(CrashReport.secrets("", "", "bob").contains("bob"))
    }

    @Test
    fun secretsOfEveryProfile() {
        val work = CrashReport.Setup("remote vpn.example.com 443\n", "vless://11111111-2222-3333-4444-555555555555@edge.example.net:443", "alex")
        val home = CrashReport.Setup("remote home.example.org 1194\n", "", "sam.home")
        assertEquals(
            listOf(
                "11111111-2222-3333-4444-555555555555", "edge.example.net", "home.example.org",
                "vpn.example.com", "sam.home", "alex",
            ),
            CrashReport.secrets(listOf(work, home, work)),
        )
    }

    @Test
    fun secretsOfAProxyLink() {
        val secrets = CrashReport.secrets("", "socks5://alex.smith:hunter22@proxy.example.org:1080#office", "")
        assertEquals(
            setOf("proxy.example.org", "alex.smith", "alex.smith:hunter22", "hunter22"),
            secrets.toSet(),
        )
    }

    @Test
    fun secretsOfAProxyLinkWithCredentialsLast() {
        val secrets = CrashReport.secrets("", "http://proxy.example.org:8080@alex.smith:hunter22", "")
        assertEquals(
            setOf("proxy.example.org", "alex.smith", "alex.smith:hunter22", "hunter22"),
            secrets.toSet(),
        )
    }

    @Test
    fun secretsFromAVlessJsonConfig() {
        val json = """{"outbounds":[{"settings":{"vnext":[{"address": "203.0.113.9"}]},
            "streamSettings":{"tlsSettings":{"serverName":"cdn.example.com"}}}]}"""
        assertEquals(setOf("203.0.113.9", "cdn.example.com"), CrashReport.secrets("", json, "").toSet())
    }

    @Test
    fun secretsIncludeThePassword() {
        val secrets = CrashReport.secrets(listOf(CrashReport.Setup("", "", "alex", "correct horse")))
        assertTrue("correct horse" in secrets)
    }

    @Test
    fun secretsOfALinksParameters() {
        val vless = "vless://0f8e2a3c-1111-4222-8333-123456789abc@203.0.113.9:443?type=ws&security=tls" +
            "&sni=front.example.com&host=real.example.net&path=%2Fsecret-path#me"
        val secrets = CrashReport.secrets("", vless, "").toSet()
        assertTrue(secrets.containsAll(listOf("front.example.com", "real.example.net", "/secret-path")))
        val reality = "vless://0f8e2a3c-1111-4222-8333-123456789abc@edge.example.org:443?security=reality" +
            "&sni=www.example.com&pbk=Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=6ba85179e30d4fc2"
        assertTrue(
            CrashReport.secrets("", reality, "").containsAll(
                listOf("Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw", "6ba85179e30d4fc2", "www.example.com")
            )
        )
    }

    @Test
    fun secretsOfCredentialsInAJsonConfig() {
        val json = """{"outbounds":[{"protocol":"trojan","settings":{"servers":[
            {"address":"203.0.113.9","password":"trojan-pass-123"}]}},
            {"protocol":"vless","settings":{"vnext":[{"address":"edge.example.org",
            "users":[{"id":"0f8e2a3c-1111-4222-8333-123456789abc"}]}]}}]}"""
        assertTrue(
            CrashReport.secrets("", json, "").containsAll(
                listOf("trojan-pass-123", "edge.example.org", "0f8e2a3c-1111-4222-8333-123456789abc")
            )
        )
    }

    @Test
    fun redactsIpv6Addresses() {
        val text = "dial tcp [2001:db8:85a3::8a2e:370:7334]:443: i/o timeout\n" +
            "from fe80::1. and ::ffff:198.51.100.7\n" +
            "at 14:03:59 in std::string, openvpn::ClientAPI, ab::cd, Foo.kt:120"
        assertEquals(
            "dial tcp [[ip]]:443: i/o timeout\n" +
                "from [ip]. and [ip]\n" +
                "at 14:03:59 in std::string, openvpn::ClientAPI, ab::cd, Foo.kt:120",
            CrashReport.redact(text, emptyList()),
        )
    }

    @Test
    fun redactsServersUuidsAndAddresses() {
        val text = "java.net.UnknownHostException: Unable to resolve host \"VPN.example.com\"\n" +
            "connect to 198.51.100.7:1194 failed, id 0f8e2a3c-1111-4222-8333-123456789abc\n" +
            "at io.github.meshkati.ovpnvless.ChainVpnService.run(ChainVpnService.kt:120)\n" +
            "Android 16.0.1"
        assertEquals(
            "java.net.UnknownHostException: Unable to resolve host \"[server]\"\n" +
                "connect to [ip]:1194 failed, id [uuid]\n" +
                "at io.github.meshkati.ovpnvless.ChainVpnService.run(ChainVpnService.kt:120)\n" +
                "Android 16.0.1",
            CrashReport.redact(text, listOf("vpn.example.com")),
        )
    }

    @Test
    fun bodyHasDeviceAndRedactedDetails() {
        val crash = Crash(Crash.Kind.JAVA, 1_798_200_000_000, "thread \"main\"\njava.lang.IllegalStateException: alex.smith")
        val body = CrashReport.body(crash, device, listOf("alex.smith"))
        assertTrue(body, body.startsWith("**What happened:** App crash (Kotlin/Java)\n**When:** 2026-12-25 12:00 UTC\n"))
        assertTrue(body.contains("**Device:** Google Pixel 10 Pro\n"))
        assertTrue(body.contains("**Route:** OpenVPN → VLESS\n"))
        assertTrue(body.endsWith("```text\nthread \"main\"\njava.lang.IllegalStateException: [server]\n```\n"))
        assertEquals("Crash: App crash (Kotlin/Java) in 0.2.0", CrashReport.title(crash, "0.2.0"))
    }

    @Test
    fun shortReportGoesWhole() {
        val body = CrashReport.body(Crash(Crash.Kind.NATIVE, 0, "signal 11 (SIGSEGV)"), device, emptyList())
        val url = CrashReport.issueUrl("Crash", body)
        assertTrue(url.startsWith("https://github.com/Meshkati/android-openvpn-socks/issues/new?title=Crash&body="))
        assertEquals(body, URLDecoder.decode(url.substringAfter("&body="), "UTF-8"))
    }

    @Test
    fun longReportIsCutToFitTheLink() {
        val trace = (1..2000).joinToString("\n") { "  at engine.frame$it(engine.go:$it)" }
        val body = CrashReport.body(Crash(Crash.Kind.GO, 0, trace), device, emptyList())
        val url = CrashReport.issueUrl("Crash", body)
        assertTrue(url.length <= CrashReport.MAX_URL)
        val sent = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(sent.startsWith("**What happened:** Engine crash"))
        assertTrue(sent.contains("  at engine.frame1(engine.go:1)\n"))
        assertTrue(sent.endsWith("Use Share in the app for the full report.\n```\n"))
    }
}
