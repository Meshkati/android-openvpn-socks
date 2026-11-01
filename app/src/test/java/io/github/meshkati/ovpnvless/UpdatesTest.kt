package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun tagAndVersionNameParseTheSame() {
        assertEquals(Version(0, 2, 10), Version.parse("v0.2.10"))
        assertEquals(Version(0, 2, 10), Version.parse("0.2.10"))
    }

    @Test
    fun localAndDebugVersionsDoNotParse() {
        assertNull(Version.parse("0.1"))
        assertNull(Version.parse("0.1-debug"))
        assertNull(Version.parse("1.2.3-debug"))
        assertNull(Version.parse("0.0.0-pr12"))
        assertNull(Version.parse("v1.02.3"))
        assertNull(Version.parse(""))
    }

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(Version(0, 10, 0) > Version(0, 9, 9))
        assertTrue(Version(1, 0, 0) > Version(0, 99, 99))
        assertEquals("0.10.0", Version(0, 10, 0).toString())
    }

    @Test
    fun onlyReleaseBuildsCheck() {
        assertTrue(Updates.checks("0.2.0"))
        assertFalse(Updates.checks("0.1"))
        assertFalse(Updates.checks("0.2.0-debug"))
    }

    @Test
    fun newerReleaseIsOffered() {
        assertEquals(Version(0, 3, 0), Updates.offer("0.2.0", "v0.3.0", dismissed = ""))
    }

    @Test
    fun sameOrOlderReleaseIsNotOffered() {
        assertNull(Updates.offer("0.2.0", "v0.2.0", dismissed = ""))
        assertNull(Updates.offer("0.2.0", "v0.1.9", dismissed = ""))
    }

    @Test
    fun nothingIsOfferedBeforeTheFirstCheckOrForAnOddTag() {
        assertNull(Updates.offer("0.2.0", "", dismissed = ""))
        assertNull(Updates.offer("0.2.0", "nightly", dismissed = ""))
    }

    @Test
    fun aDismissedReleaseStaysHiddenButANewerOneShows() {
        assertNull(Updates.offer("0.2.0", "v0.3.0", dismissed = "v0.3.0"))
        assertEquals(Version(0, 3, 1), Updates.offer("0.2.0", "v0.3.1", dismissed = "v0.3.0"))
    }

    @Test
    fun releasePageIsThisRepositorysTag() {
        assertEquals(
            "https://github.com/Meshkati/android-openvpn-socks/releases/tag/v0.3.0",
            Updates.releasePage(Version(0, 3, 0)),
        )
    }

    @Test
    fun checkIsDueOnceTheWaitIsOver() {
        assertTrue(Updates.isDue(now = 0, next = 0))
        assertTrue(Updates.isDue(now = 10 * hour, next = 10 * hour))
        assertFalse(Updates.isDue(now = 10 * hour, next = 11 * hour))
    }

    @Test
    fun clockTurnedBackDoesNotBlockChecks() {
        assertTrue(Updates.isDue(now = 0, next = 25 * hour))
    }
}
