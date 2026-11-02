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
import android.widget.TextView
import android.widget.Toast
import engine.Engine

/**
 * About: the app version and the versions of the two cores it is built with,
 * Xray-core (the Go engine) and OpenVPN 3 core (JNI), and a link to the source.
 */
class AboutActivity : Activity() {

    companion object {
        /** Read once per process: loading the Go engine is slow ([Crashes.watchEngine]). */
        @Volatile
        private var cores: Pair<String?, String?>? = null
    }

    private lateinit var xray: TextView
    private lateinit var openVpn: TextView
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
