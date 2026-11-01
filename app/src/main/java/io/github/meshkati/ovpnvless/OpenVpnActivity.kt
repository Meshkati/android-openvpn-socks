package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

/** OpenVPN profile: import or edit the .ovpn, and the username and password. */
class OpenVpnActivity : Activity() {

    companion object {
        private const val REQUEST_IMPORT = 1
    }

    private lateinit var settings: Settings
    private lateinit var summaryTitle: TextView
    private lateinit var summaryMeta: TextView
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var togglePassword: ImageButton
    private var passwordShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_openvpn)
        edgeToEdge(findViewById(R.id.root))
        settings = Settings(this)

        summaryTitle = findViewById(R.id.summary_title)
        summaryMeta = findViewById(R.id.summary_meta)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        togglePassword = findViewById(R.id.toggle_password)

        username.setText(settings.username)
        password.setText(settings.password)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.import_file).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_IMPORT)
        }
        findViewById<View>(R.id.edit_text).setOnClickListener {
            startActivity(Intent(this, ProfileTextActivity::class.java))
        }
        togglePassword.setOnClickListener { showPassword(!passwordShown) }
    }

    override fun onStart() {
        super.onStart()
        renderSummary() // the profile text screen may have changed it
    }

    override fun onPause() {
        settings.username = username.text.toString()
        settings.password = password.text.toString()
        super.onPause()
    }

    private fun showPassword(show: Boolean) {
        passwordShown = show
        val selection = password.selectionEnd
        password.inputType = InputType.TYPE_CLASS_TEXT or
            if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.setSelection(selection.coerceIn(0, password.length()))
        togglePassword.setImageResource(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye)
        togglePassword.contentDescription = getString(if (show) R.string.hide_password else R.string.show_password)
    }

    private fun renderSummary() {
        val info = ProfileInfo.parse(settings.profile)
        if (info == null) {
            summaryTitle.setText(R.string.profile_none)
            summaryMeta.setText(R.string.profile_none_meta)
            return
        }
        summaryTitle.text = info.host ?: getString(R.string.profile_no_server)
        summaryMeta.text = buildString {
            append(getString(R.string.profile_meta, info.protocol, info.port))
            if (info.challenge != null) append(" · ").append(getString(R.string.profile_asks_code))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            return
        }
        settings.profile = text
        renderSummary()
    }
}
