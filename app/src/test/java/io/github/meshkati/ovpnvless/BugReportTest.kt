package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.time.ZoneId

class BugReportTest {

    private val device = CrashReport.Device(
        app = "0.4.0",
        android = "16 (API 36), arm64-v8a",
        model = "Google Pixel 10 Pro",
        route = "OpenVPN → VLESS",
    )

    // 2026-11-12 10:00:00 UTC
    private val time = 1_794_477_600_000L
    private val zone = ZoneId.of("Asia/Tehran") // UTC+03:30

    private val secrets = CrashReport.secrets("remote vpn.example.com 1194\n", "", "alex.smith")

    private fun snapshot(log: List<String> = emptyList(), sections: List<BugReport.Section> = emptyList()) =
        BugReport.Snapshot(
            time = time,
            zone = zone,
            device = device,
            sections = sections,
            log = log.mapIndexed { i, line -> BugReport.LogEntry(time - (log.size - i) * 1000L, line) },
        )

    private fun bodyOf(url: String) = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")

    @Test
    fun titleIsTheFirstLineOfWhatTheUserWrote() {
        assertEquals("Bug report from 0.4.0", BugReport.title("  \n ", "0.4.0"))
        assertEquals("Stuck on Connected", BugReport.title("\n  Stuck on Connected \nafter battery saver", "0.4.0"))
        val long = BugReport.title("x".repeat(200), "0.4.0")
        assertEquals(80, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun bodyHasTheDeviceStateAndTimedLogWithSecretsHidden() {
        val body = BugReport.body(
            snapshot(
                log = listOf("openvpn: connecting to vpn.example.com 203.0.113.9:1194", "service: disconnect"),
                sections = listOf(BugReport.Section("VPN", "app state: CONNECTED (10.8.0.6)")),
            ),
            "It said Connected, user alex.smith",
            secrets,
        )
        assertTrue(body, body.contains("**What happened:**\nIt said Connected, user [server]\n"))
        assertTrue(body, body.contains("**Device:** Google Pixel 10 Pro\n"))
        assertTrue(body, body.contains("**Taken:** 2026-11-12 13:30:00 (phone time, UTC+03:30)\n"))
        assertTrue(body, body.contains("### VPN\n```text\napp state: CONNECTED ([ip])\n```\n"))
        assertTrue(body, body.contains("### Log (2 lines, phone time)\n```text\n"))
        assertTrue(body, body.contains("11-12 13:29:58.000  openvpn: connecting to [server] [ip]:1194\n"))
        assertTrue(body, body.contains("11-12 13:29:59.000  service: disconnect\n```"))
        assertFalse(body.contains("vpn.example.com"))
        assertFalse(body.contains("203.0.113.9"))
    }

    @Test
    fun withoutADescriptionItAsksForOne() {
        val body = BugReport.body(snapshot(), "", secrets)
        assertTrue(body.contains("**What happened:**\n(Describe what you saw and what you expected.)\n"))
        assertTrue(body.contains("### Log\n\nNothing logged since the app started.\n"))
    }

    @Test
    fun appsDestinationsAreHidden() {
        assertEquals(
            "xray: failed to dial tcp:[host]:443 and udp:[host]:53, server [server] tcp:[ip]:80",
            BugReport.redact(
                "xray: failed to dial tcp:www.example.com:443 and udp:dns.google:53, server vpn.example.com tcp:198.51.100.7:80",
                secrets,
            ),
        )
    }

    @Test
    fun aFenceInsideTheTextDoesNotEndTheBlock() {
        val body = BugReport.body(snapshot(listOf("xray: ```oops")), "", secrets)
        assertTrue(body.contains("xray: '''oops"))
    }

    @Test
    fun aShortReportFitsTheLinkWhole() {
        val issue = BugReport.issue("t", snapshot(listOf("a", "b")), "", secrets, "CUT")
        assertTrue(issue.complete)
        assertEquals(BugReport.body(snapshot(listOf("a", "b")), "", secrets), bodyOf(issue.url))
    }

    @Test
    fun aLongReportKeepsTheNewestLogLinesAndBriefSections() {
        val log = (1..400).map { "openvpn: line $it " + "x".repeat(60) }
        val sections = listOf(
            BugReport.Section("Power", "battery saver: on"),
            BugReport.Section("Other threads", "\"main\" RUNNABLE\n" + "  at frame\n".repeat(50), brief = false),
        )
        val issue = BugReport.issue("t", snapshot(log, sections), "stuck", secrets, "CUT")
        assertFalse(issue.complete)
        assertTrue(issue.url.length <= CrashReport.MAX_URL)
        val body = bodyOf(issue.url)
        assertTrue(body, body.startsWith("> CUT\n\n**What happened:**\nstuck\n"))
        assertTrue(body.contains("### Power\n```text\nbattery saver: on\n```"))
        assertFalse(body.contains("Other threads"))
        assertTrue(body.contains("openvpn: line 400 "))
        assertFalse(body.contains("openvpn: line 1 "))
        assertTrue(Regex("""### Log \(last \d+ of 400 lines, phone time\)""").containsMatchIn(body))
        // As many lines as fit: one more would not.
        val kept = Regex("""last (\d+) of""").find(body)!!.groupValues[1].toInt()
        val oneMore = BugReport.body(snapshot(log, sections), "stuck", secrets, kept + 1, brief = true, note = "CUT")
        assertTrue(
            "${CrashReport.ISSUE_URL}?title=t&body=${java.net.URLEncoder.encode(oneMore, "UTF-8")}".length >
                CrashReport.MAX_URL,
        )
    }

    @Test
    fun durations() {
        assertEquals("0s", BugReport.duration(-5))
        assertEquals("12s", BugReport.duration(12_999))
        assertEquals("4m 5s", BugReport.duration(245_000))
        assertEquals("3h 12m", BugReport.duration((3 * 3600 + 12 * 60) * 1000L))
        assertEquals("2d 3h", BugReport.duration((2 * 86_400 + 3 * 3600 + 59) * 1000L))
    }

    @Test
    fun androidCodesHaveNames() {
        assertEquals("killed for low memory", BugReport.exitReason(3))
        assertEquals("unknown reason (99)", BugReport.exitReason(99))
        assertEquals("foreground service", BugReport.importance(125))
        assertEquals("restricted", BugReport.standbyBucket(45))
    }
}
