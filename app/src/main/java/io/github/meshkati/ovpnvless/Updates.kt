package io.github.meshkati.ovpnvless

/**
 * A release version. The release workflow makes tags vMAJOR.MINOR.PATCH (each
 * 0-99) and sets versionName to MAJOR.MINOR.PATCH.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version) =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""v?(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)""")

        /**
         * "v1.2.3" or "1.2.3". Null for anything else, such as a local build's
         * "0.1" or a debug build's "0.1-debug".
         */
        fun parse(s: String): Version? =
            PATTERN.matchEntire(s.trim())?.destructured?.let { (major, minor, patch) ->
                Version(major.toInt(), minor.toInt(), patch.toInt())
            }
    }
}

/**
 * Telling the user about a newer release on GitHub. The app only links to the
 * release page: the user downloads the APK there, and Android installs it over
 * this one only if it is signed with the same key.
 */
object Updates {
    const val REPO = "Meshkati/android-openvpn-socks"

    /** Latest release, not counting drafts and pre-releases. */
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** Sent with each check, as GitHub requires a User-Agent ([fetchLatestRelease]). */
    fun userAgent(installed: String) = "ovpnvless/$installed (Android)"

    /** How the app's own request to GitHub leaves ([fetchLatestRelease]). */
    enum class Way {
        /** From the app: the VPN is off, or OpenVPN only, which the app is inside of. */
        DIRECT,

        /** Through Xray, as apps' traffic goes: with VLESS the app is outside its own VPN. */
        XRAY,
    }

    /**
     * The way a check started now would go, or null while a VLESS session is
     * connecting or disconnecting. Then the app is already, or still, outside
     * its own VPN, so a plain request would show GitHub the phone's real IP,
     * and Xray is not there to carry it.
     */
    fun way(phase: VpnState.Phase, route: Route): Way? = when {
        phase == VpnState.Phase.DISCONNECTED || route == Route.OPENVPN_ONLY -> Way.DIRECT
        phase == VpnState.Phase.CONNECTED -> Way.XRAY
        else -> null
    }

    /** After a check that worked, the next one waits a day. */
    const val INTERVAL_OK = 24L * 60 * 60 * 1000

    /** After one that failed (offline, GitHub blocked or rate-limited), an hour. */
    const val INTERVAL_FAILED = 60L * 60 * 1000

    /**
     * Only builds from a release tag check. Local and debug builds have no such
     * version, and a debug build has its own app id, so a release APK would not
     * update it anyway.
     */
    fun checks(installed: String) = Version.parse(installed) != null

    /** Whether to ask GitHub now. A [next] time over a day away means the clock was turned back. */
    fun isDue(now: Long, next: Long) = now >= next || next - now > INTERVAL_OK

    /**
     * The release to tell the user about: [latest] if it is newer than
     * [installed] and the user has not tapped "Later" on it (or on a newer one).
     */
    fun offer(installed: String, latest: String, dismissed: String): Version? {
        val current = Version.parse(installed) ?: return null
        val newest = Version.parse(latest)?.takeIf { it > current } ?: return null
        val skipped = Version.parse(dismissed)
        return if (skipped != null && newest <= skipped) null else newest
    }

    /** What a check the user asked for on the About screen found. */
    sealed class Found {
        /** No answer from GitHub, or a tag that is not vMAJOR.MINOR.PATCH. */
        object Failed : Found()

        /** [installed] is the latest release, or newer. */
        object UpToDate : Found()

        /** A local or debug build, which does not update; [latest] is the newest release. */
        data class NotARelease(val latest: Version) : Found()

        data class Newer(val version: Version) : Found()
    }

    /**
     * The answer to the user's own check: unlike [offer], it shows a newer
     * release even if the user tapped "Later" on it, since they asked.
     * [latest] is the tag GitHub gave, null if the request failed.
     */
    fun found(installed: String, latest: String?): Found {
        val newest = latest?.let(Version::parse) ?: return Found.Failed
        val current = Version.parse(installed) ?: return Found.NotARelease(newest)
        return if (newest > current) Found.Newer(newest) else Found.UpToDate
    }

    /**
     * Built from the version rather than taken from GitHub's answer, so the
     * link always points at this repository's releases.
     */
    fun releasePage(version: Version) = "https://github.com/$REPO/releases/tag/v$version"
}
