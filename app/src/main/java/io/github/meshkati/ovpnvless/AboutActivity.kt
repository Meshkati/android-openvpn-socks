package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import engine.Engine

/**
 * About: the app version and the versions of the two cores it is built with,
 * Xray-core (the Go engine) and OpenVPN 3 core (JNI), a link to the source,
 * and a check for a newer release that the user starts ([Updates]).
 */
class AboutActivity : Activity() {

    companion object {
        /** Read once per process: loading the Go engine is slow ([Crashes.watchEngine]). */
        @Volatile
        private var cores: Pair<String?, String?>? = null
    }

    private lateinit var xray: TextView
    private lateinit var openVpn: TextView
    private lateinit var updateCheck: Button
    private lateinit var updateStatus: TextView

    /** The newer release the last check found: the button then opens its page. */
    private var newer: Version? = null
    private var versions = About.Versions(BuildConfig.VERSION_NAME, null, null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        edgeToEdge(findViewById(R.id.root))

        xray = findViewById(R.id.version_xray)
        openVpn = findViewById(R.id.version_openvpn)
        findViewById<TextView>(R.id.version_app).text = BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.copy).setOnClickListener { copy() }
        findViewById<View>(R.id.about_source).setOnClickListener { open(About.SOURCE_URL) }

        updateCheck = findViewById(R.id.update_check)
        updateStatus = findViewById(R.id.update_status)
        updateCheck.setOnClickListener {
            val version = newer
            if (version != null) open(Updates.releasePage(version)) else checkForUpdate()
        }

        val known = cores
        if (known != null) {
            show(known)
        } else {
            Thread({
                val read = readCores()
                cores = read
                runOnUiThread { if (!isDestroyed) show(read) }
            }, "about-versions").apply { isDaemon = true }.start()
        }
    }

    private fun readCores(): Pair<String?, String?> {
        val x = runCatching {
            Crashes.watchEngine(this)
            Engine.xrayVersion()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val o = runCatching { About.openVpnVersion(OpenVpn3.coreVersion()) }.getOrNull()
        return x to o
    }

    private fun show(cores: Pair<String?, String?>) {
        versions = versions.copy(xray = cores.first, openVpn = cores.second)
        xray.text = cores.first ?: getString(R.string.about_unknown)
        openVpn.text = cores.second ?: getString(R.string.about_unknown)
    }

    /**
     * Asks GitHub for the latest release now. A check that works also counts
     * as the home screen's daily one, so its banner shows a newer release too.
     */
    private fun checkForUpdate() {
        val installed = BuildConfig.VERSION_NAME
        updateCheck.isEnabled = false
        showStatus(getString(R.string.update_checking))
        Thread({
            val tag = fetchLatestRelease(Updates.userAgent(installed))
            runOnUiThread {
                if (tag != null) {
                    val settings = Settings(this)
                    settings.latestRelease = tag
                    settings.nextUpdateCheck = System.currentTimeMillis() + Updates.INTERVAL_OK
                }
                if (!isDestroyed) showFound(Updates.found(installed, tag))
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    private fun showFound(found: Updates.Found) {
        updateCheck.isEnabled = true
        when (found) {
            is Updates.Found.Newer -> {
                newer = found.version
                updateCheck.text = getString(R.string.update_download_version, found.version.toString())
                showStatus(getString(R.string.update_available, found.version.toString()))
            }
            is Updates.Found.NotARelease ->
                showStatus(getString(R.string.update_not_release, "v${found.latest}"))
            Updates.Found.UpToDate -> showStatus(getString(R.string.update_up_to_date))
            Updates.Found.Failed -> showStatus(getString(R.string.update_check_failed))
        }
    }

    private fun showStatus(text: String) {
        updateStatus.text = text
        updateStatus.visibility = View.VISIBLE
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.about), About.text(versions, getString(R.string.about_unknown)))
        )
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.about_copied, Toast.LENGTH_SHORT).show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }
}
