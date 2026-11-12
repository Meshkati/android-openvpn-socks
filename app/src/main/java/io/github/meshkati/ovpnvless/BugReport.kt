package io.github.meshkati.ovpnvless

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A bug report the user starts from the home screen ([BugReportActivity]):
 * a snapshot of the app's state, what Android says about power, networks and
 * past exits of the process, the threads, and the app log, with the servers,
 * credentials, UUIDs and IP addresses hidden ([CrashReport.redact]). Like a
 * crash report, the user reads it and sends it themselves: a prefilled GitHub
 * issue, or any app through the share sheet.
 */
object BugReport {

    /** One line of the app log ([VpnState]) and when it was logged. */
    data class LogEntry(val time: Long, val line: String)

    /**
     * A part of the snapshot, as "label: value" lines or a stack. [brief]
     * parts stay in a GitHub link that has to be cut; the others are left out first.
     */
    data class Section(val title: String, val text: String, val brief: Boolean = true)

    data class Snapshot(
        val time: Long,
        val zone: ZoneId,
        val device: CrashReport.Device,
        val sections: List<Section>,
        val log: List<LogEntry>,
    )

    /** A new-issue link, and whether it holds the whole report. */
    data class Issue(val url: String, val complete: Boolean)

    private const val TITLE_MAX = 80

    // Xray names apps' destinations as "tcp:example.com:443": not the user's to share by accident.
    private val DESTINATION = Regex("""\b(tcp|udp):[A-Za-z0-9.-]*[A-Za-z][A-Za-z0-9.-]*\.[A-Za-z]{2,}(:\d+)""")

    /** The first line of what the user wrote, else a generic title. */
    fun title(description: String, app: String): String {
        val first = description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return "Bug report from $app"
        return if (first.length <= TITLE_MAX) first else first.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    /** [text] with [CrashReport.redact] applied and apps' destinations hidden. */
    fun redact(text: String, secrets: List<String>): String =
        DESTINATION.replace(CrashReport.redact(text, secrets)) { "${it.groupValues[1]}:[host]${it.groupValues[2]}" }

    /**
     * Markdown, for a GitHub issue; also fine as plain text. [logLines] keeps
     * the newest lines of the log; [brief] leaves out the sections that are not.
     */
    fun body(
        snapshot: Snapshot,
        description: String,
        secrets: List<String>,
        logLines: Int = snapshot.log.size,
        brief: Boolean = false,
        note: String = "",
    ): String {
        val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(snapshot.zone)
        val offset = snapshot.zone.rules.getOffset(Instant.ofEpochMilli(snapshot.time)).id.replace("Z", "+00:00")
        val what = redact(description.trim(), secrets).ifEmpty { "(Describe what you saw and what you expected.)" }
        val log = snapshot.log.takeLast(logLines.coerceAtLeast(0))
        return buildString {
            if (note.isNotEmpty()) append("> ").append(note).append("\n\n")
            append("**What happened:**\n").append(what).append("\n\n")
            append("**App:** ${snapshot.device.app}\n")
            append("**Android:** ${snapshot.device.android}\n")
            append("**Device:** ${snapshot.device.model}\n")
            append("**Route:** ${snapshot.device.route}\n")
            append("**Taken:** ${clock.format(Instant.ofEpochMilli(snapshot.time))} (phone time, UTC$offset)\n")
            for (section in snapshot.sections) {
                if (brief && !section.brief) continue
                append("\n### ").append(section.title).append('\n')
                block(redact(section.text, secrets))
            }
            append("\n### Log")
            when {
                snapshot.log.isEmpty() -> append("\n\nNothing logged since the app started.\n")
                log.size < snapshot.log.size ->
                    append(" (last ${log.size} of ${snapshot.log.size} lines, phone time)\n")
                else -> append(" (${log.size} lines, phone time)\n")
            }
            if (log.isNotEmpty()) block(log.joinToString("\n") { logLine(it, snapshot.zone, secrets) })
        }
    }

    private fun StringBuilder.block(text: String) {
        // A fence inside would end the block early.
        append("```text\n").append(text.trimEnd().replace("```", "'''")).append("\n```\n")
    }

    private val LOG_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    private fun logLine(entry: LogEntry, zone: ZoneId, secrets: List<String>): String =
        LOG_TIME.withZone(zone).format(Instant.ofEpochMilli(entry.time)) + "  " + redact(entry.line, secrets)

    /**
     * A new-issue link with the report filled in. When it is too long for
     * GitHub, the sections that are not brief go first, then the oldest log
     * lines, and the body starts with [cutNote]: the app puts the full report
     * on the clipboard for the user to paste.
     */
    fun issue(title: String, snapshot: Snapshot, description: String, secrets: List<String>, cutNote: String): Issue {
        fun url(body: String) = "${CrashReport.ISSUE_URL}?title=${enc(title)}&body=${enc(body)}"
        val full = url(body(snapshot, description, secrets))
        if (full.length <= CrashReport.MAX_URL) return Issue(full, complete = true)

        // The most log lines that still fit, newest kept.
        fun cut(lines: Int) = url(body(snapshot, description, secrets, lines, brief = true, note = cutNote))
        var low = 0
        var high = snapshot.log.size
        if (cut(low).length > CrashReport.MAX_URL) {
            // Even without the log it is too long: only the note, and the user pastes it all.
            return Issue(url("> $cutNote\n"), complete = false)
        }
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (cut(mid).length <= CrashReport.MAX_URL) low = mid else high = mid - 1
        }
        return Issue(cut(low), complete = false)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** E.g. "2d 3h", "3h 12m", "4m 5s", "12s". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val (d, h, m) = Triple(s / 86_400, s / 3600 % 24, s / 60 % 60)
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    /** ApplicationExitInfo.REASON_*. */
    fun exitReason(reason: Int): String = when (reason) {
        0 -> "unknown reason"
        1 -> "exited by itself"
        2 -> "killed by a signal"
        3 -> "killed for low memory"
        4 -> "crashed (Kotlin/Java)"
        5 -> "crashed (native)"
        6 -> "not responding (ANR)"
        7 -> "failed to start"
        8 -> "killed for a permission change"
        9 -> "killed for using too much (CPU or battery)"
        10 -> "killed at the user's request (force stop, or swiped away)"
        11 -> "stopped by the user from the task manager"
        12 -> "a process it depends on died"
        13 -> "killed by Android (other)"
        14 -> "frozen, then killed"
        15 -> "killed for a package state change"
        16 -> "app updated"
        else -> "unknown reason ($reason)"
    }

    /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_*. */
    fun importance(importance: Int): String = when (importance) {
        100 -> "foreground"
        125 -> "foreground service"
        150 -> "top, sleeping"
        200 -> "visible"
        230 -> "perceptible"
        300 -> "service"
        325 -> "top, sleeping"
        350 -> "can't save state"
        400 -> "cached"
        500 -> "empty"
        1000 -> "gone"
        else -> "importance $importance"
    }

    /** UsageStatsManager.STANDBY_BUCKET_*: how far Android holds the app back in the background. */
    fun standbyBucket(bucket: Int): String = when (bucket) {
        5 -> "exempted"
        10 -> "active"
        20 -> "working set"
        30 -> "frequent"
        40 -> "rare"
        45 -> "restricted"
        50 -> "never"
        else -> "bucket $bucket"
    }
}
