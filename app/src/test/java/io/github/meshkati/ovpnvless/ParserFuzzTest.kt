package io.github.meshkati.ovpnvless

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * The parsers read text pasted or imported by the user (a link from a chat, a
 * .ovpn from anywhere) and run on the home screen for every redraw: an
 * exception there crashes the app on every start. Mutated real inputs must
 * never throw. Seeded, so a failure reproduces; -Dfuzz.iterations=N runs longer.
 */
class ParserFuzzTest {

    private val iterations = System.getProperty("fuzz.iterations")?.toIntOrNull() ?: 3000

    private val links = listOf(
        "vless://0f8e2a3c-1111-4222-8333-123456789abc@edge.example.org:443?type=ws&security=tls&sni=a.example&path=%2Fws&fp=chrome#name",
        "vless://id@[2001:db8::1]:8443?type=grpc&serviceName=svc&security=reality&pbk=abc&sid=12&fp=randomized&flow=xtls-rprx-vision",
        "socks5://alex.smith:hunter22@proxy.example.org:1080#office",
        "socks://dXNlcjpwYXNz@1.2.3.4:1080",
        "http://proxy.example.org:8080@alex.smith:hunter22",
        "https://user:p%40ss@proxy.example.org:443?sni=s.example&fp=chrome",
        """{"inbounds":[{"protocol":"tun"}],"outbounds":[{"protocol":"freedom"}]}""",
    )

    private val profiles = listOf(
        """
        client
        dev tun
        proto udp
        remote vpn.example.com 1194
        remote-random
        static-challenge "Enter code" 1
        <connection>
        remote backup.example.net 443 tcp
        </connection>
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIB
        -----END CERTIFICATE-----
        </ca>
        """.trimIndent(),
        "remote \"quoted host\" 443 tcp-client\nport 1195\nproto tcp\nstatic-challenge 'It''s \\\"x' 0",
    )

    private val alphabet = "%@:/?#&=[]{}\"'\\ \n\t+-._~,;0123456789abcdefABCDEF\u0000é۱🚀"

    private fun mutate(r: Random, s: String): String {
        val b = StringBuilder(s)
        repeat(1 + r.nextInt(6)) {
            val at = if (b.isEmpty()) 0 else r.nextInt(b.length + 1)
            when (r.nextInt(5)) {
                0 -> b.insert(at, alphabet[r.nextInt(alphabet.length)])
                1 -> if (at < b.length) b.deleteCharAt(at)
                2 -> if (at < b.length) b.setCharAt(at, alphabet[r.nextInt(alphabet.length)])
                3 -> b.setLength(minOf(b.length, at)) // cut it short
                else -> if (b.isNotEmpty()) b.insert(at, b.substring(r.nextInt(b.length)))
            }
        }
        return b.toString()
    }

    private fun check(input: String, what: String, block: (String) -> Unit) {
        try {
            block(input)
        } catch (e: Exception) {
            fail("$what threw $e for input: ${input.replace("\n", "\\n")}")
        }
    }

    @Test
    fun linkParsersNeverThrow() {
        val r = Random(20261105)
        repeat(iterations) {
            val input = mutate(r, links[r.nextInt(links.size)])
            check(input, "VlessConfig.parse") { VlessConfig.parse(it) }
            check(input, "VlessConfig.standardProxyLink") { VlessConfig.standardProxyLink(it) }
            check(input, "VlessConfig.isSocks4") { VlessConfig.isSocks4(it) }
            check(input, "Fingerprint.risky") { Fingerprint.risky(it) }
            check(input, "Fingerprint.fix") { Fingerprint.fix(it) }
            check(input, "CrashReport.secrets") { CrashReport.secrets("", it, "") }
        }
    }

    @Test
    fun profileParserNeverThrows() {
        val r = Random(20261106)
        repeat(iterations) {
            val input = mutate(r, profiles[r.nextInt(profiles.size)])
            check(input, "ProfileInfo.parse") { ProfileInfo.parse(it) }
            check(input, "CrashReport.secrets") { CrashReport.secrets(it, "", "") }
        }
    }
}
