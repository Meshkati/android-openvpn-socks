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

/** OpenVPN config: import or edit a profile's .ovpn, and the username and password. */
class OpenVpnActivity : Activity() {

    companion object {
        private const val REQUEST_IMPORT = 1

        /** Bigger than any real profile, even with inline certificates and keys. */
        private const val MAX_IMPORT = 1 shl 20
    }

    private lateinit var profile: Profile
    private lateinit var summaryTitle: TextView
    private lateinit var summaryMeta: TextView
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var togglePassword: ImageButton
    private var passwordShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepOffScreenshots()
        setContentView(R.layout.activity_openvpn)
        edgeToEdge(findViewById(R.id.root))
        profile = Profiles.of(this) ?: return finish() // deleted meanwhile
        showProfileName(profile)

        summaryTitle = findViewById(R.id.summary_title)
        summaryMeta = findViewById(R.id.summary_meta)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        togglePassword = findViewById(R.id.toggle_password)

        username.setText(profile.username)
        password.setText(profile.password)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.import_file).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_IMPORT)
        }
        findViewById<View>(R.id.edit_text).setOnClickListener {
            startActivity(
                Intent(this, ProfileTextActivity::class.java).putExtra(Profiles.EXTRA_PROFILE, profile.id)
            )
        }
        togglePassword.setOnClickListener { showPassword(!passwordShown) }
    }

    override fun onStart() {
        super.onStart()
        renderSummary() // the profile text screen may have changed it
    }

    override fun onPause() {
        if (this::profile.isInitialized) {
            profile.username = username.text.toString()
            profile.password = password.text.toString()
        }
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
        val info = ProfileInfo.parse(profile.ovpn)
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
        val bytes = try {
            // Any app's file can be picked: read no more than a profile can be.
            contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_IMPORT + 1) }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (bytes.size > MAX_IMPORT) {
            Toast.makeText(this, R.string.import_too_big, Toast.LENGTH_LONG).show()
            return
        }
        val text = bytes.decodeToString()
        profile.ovpn = text
        renderSummary()
    }
}
