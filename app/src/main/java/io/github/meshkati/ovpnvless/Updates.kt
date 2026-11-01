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

    /**
     * Built from the version rather than taken from GitHub's answer, so the
     * link always points at this repository's releases.
     */
    fun releasePage(version: Version) = "https://github.com/$REPO/releases/tag/v$version"
}
