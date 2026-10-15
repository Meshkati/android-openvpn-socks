package io.github.meshkati.ovpnvless

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_VPN = 1
        private const val REQUEST_IMPORT = 2
    }

    private lateinit var settings: Settings
    private lateinit var profile: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var otp: EditText
    private lateinit var vless: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        profile = findViewById(R.id.profile)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        otp = findViewById(R.id.otp)
        vless = findViewById(R.id.vless)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        profile.setText(settings.profile)
        username.setText(settings.username)
        password.setText(settings.password)
        vless.setText(settings.vless)

        findViewById<Button>(R.id.import_profile).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            startActivityForResult(pick, REQUEST_IMPORT)
        }
        toggle.setOnClickListener {
            if (VpnState.phase == VpnState.Phase.DISCONNECTED) connect() else disconnect()
        }
    }

    override fun onStart() {
        super.onStart()
        VpnState.observe(::render)
    }

    override fun onStop() {
        VpnState.observe(null)
        save()
        super.onStop()
    }

    private fun save() {
        settings.profile = profile.text.toString()
        settings.username = username.text.toString()
        settings.password = password.text.toString()
        settings.vless = vless.text.toString().trim()
    }

    private fun connect() {
        save()
        if (settings.profile.isBlank() || settings.vless.isBlank()) {
            Toast.makeText(this, "Enter an OpenVPN profile and a VLESS link", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        startForegroundService(
            Intent(this, ChainVpnService::class.java)
                .setAction(ChainVpnService.ACTION_CONNECT)
                .putExtra(ChainVpnService.EXTRA_OTP, otp.text.toString().trim())
        )
        otp.text.clear() // a code works only once
    }

    private fun disconnect() {
        startService(Intent(this, ChainVpnService::class.java).setAction(ChainVpnService.ACTION_DISCONNECT))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_VPN -> startVpnService()
            REQUEST_IMPORT -> data?.data?.let { uri ->
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                if (text != null) profile.setText(text)
            }
        }
    }

    private fun render() {
        val phase = VpnState.phase
        status.text = buildString {
            append(phase.name.lowercase())
            if (VpnState.detail.isNotEmpty()) append(": ").append(VpnState.detail)
        }
        toggle.setText(if (phase == VpnState.Phase.DISCONNECTED) R.string.connect else R.string.disconnect)
        log.text = VpnState.logText()
    }
}
