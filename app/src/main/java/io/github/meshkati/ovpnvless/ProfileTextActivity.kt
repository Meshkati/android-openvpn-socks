package io.github.meshkati.ovpnvless

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText

/** The .ovpn profile as text, full screen. Saved when the screen is left. */
class ProfileTextActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var profile: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_text)
        edgeToEdge(findViewById(R.id.root))
        settings = Settings(this)
        profile = findViewById(R.id.profile)
        // The text is not saved in the instance state (it can be large enough
        // to crash with TransactionTooLargeException); Settings holds it.
        profile.setText(settings.profile)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
    }

    override fun onPause() {
        settings.profile = profile.text.toString()
        super.onPause()
    }
}
