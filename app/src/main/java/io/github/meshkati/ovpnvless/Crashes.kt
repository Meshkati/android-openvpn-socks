package io.github.meshkati.ovpnvless

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import engine.Engine
import java.io.File
import java.io.InputStream

/**
 * Local crash reports. Crashes are written to app-private files; on the next
 * start the latest one becomes the pending report, which the home screen
 * offers to report or share. Nothing leaves the phone unless the user taps
 * one of those, and they see the text first.
 *
 *  - Kotlin/Java: an uncaught-exception handler writes the stack trace.
 *  - Go (Xray, gVisor): the runtime writes a fatal panic to a file
 *    ([Engine.setCrashFile]).
 *  - Native (OpenVPN 3, or a signal in the engine) and ANRs: Android's own
 *    record of why the process died (ApplicationExitInfo, Android 11+; the
 *    native backtrace from Android 12).
 *
 * Only crashes of the installed version are kept.
 */
object Crashes {
    private const val TAG = "ovpn-vless"
    private const val JAVA_FILE = "java.txt"
    private const val GO_FILE = "go.txt"
    private const val PENDING_FILE = "pending.txt"
    private const val MAX_READ = 1 shl 20

    @Volatile private var engineWatched = false

    private fun dir(context: Context) = File(context.filesDir, "crash").apply { mkdirs() }

    /** First thing at process start ([App]). */
    fun start(context: Context) {
        try {
            collect(context)
        } catch (e: Throwable) {
            Log.w(TAG, "crash report: collecting failed", e)
        }
        val file = File(dir(context), JAVA_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText("thread \"${thread.name}\"\n${error.stackTraceToString()}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Before the engine first runs in this process. Loading it is slow, so not at start. */
    fun watchEngine(context: Context) {
        if (engineWatched) return
        engineWatched = true
        try {
            Engine.setCrashFile(File(dir(context), GO_FILE).path)
        } catch (e: Exception) {
            Log.w(TAG, "crash report: no Go crash file", e)
        }
    }

    fun pending(context: Context): Crash? =
        File(dir(context), PENDING_FILE).takeIf { it.isFile }?.let { Crash.decode(it.readText()) }

    fun clear(context: Context) {
        File(dir(context), PENDING_FILE).delete()
    }

    private fun collect(context: Context) {
        val dir = dir(context)
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val found = mutableListOf<Crash>()
        takeFile(File(dir, JAVA_FILE), Crash.Kind.JAVA, installed)?.let(found::add)
        takeFile(File(dir, GO_FILE), Crash.Kind.GO, installed)?.let(found::add)
        if (Build.VERSION.SDK_INT >= 30) found += exits(context, installed)
        val crash = CrashReport.pick(found) ?: return
        File(dir, PENDING_FILE).writeText(crash.encode())
    }

    private fun takeFile(file: File, kind: Crash.Kind, installed: Long): Crash? {
        if (!file.isFile) return null
        val time = file.lastModified()
        val text = file.inputStream().use { readText(it) }
        file.delete()
        return if (time >= installed && text.isNotBlank()) Crash(kind, time, text) else null
    }

    private fun exits(context: Context, installed: Long): List<Crash> {
        val settings = Settings(context)
        val seen = maxOf(settings.lastExitSeen, installed)
        val infos = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 8)
        infos.maxOfOrNull { it.timestamp }?.let { if (it > settings.lastExitSeen) settings.lastExitSeen = it }
        return infos.filter { it.timestamp > seen }.mapNotNull { info ->
            when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                    val tombstone = if (Build.VERSION.SDK_INT >= 31) {
                        info.traceInputStream?.use { Tombstone.describe(it.readBytes()) }
                    } else {
                        null
                    }
                    Crash(Crash.Kind.NATIVE, info.timestamp, tombstone ?: info.description ?: "No details")
                }
                ApplicationExitInfo.REASON_ANR -> {
                    val traces = info.traceInputStream?.use { readText(it) }
                    Crash(Crash.Kind.ANR, info.timestamp, CrashReport.anrDetails(info.description, traces))
                }
                // REASON_CRASH is a Kotlin/Java crash: the handler wrote a better one.
                else -> null
            }
        }
    }

    /** Up to [MAX_READ] bytes (InputStream.readNBytes needs API 33). */
    private fun readText(input: InputStream): String {
        val buf = ByteArray(MAX_READ)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return String(buf, 0, n, Charsets.UTF_8)
    }
}
