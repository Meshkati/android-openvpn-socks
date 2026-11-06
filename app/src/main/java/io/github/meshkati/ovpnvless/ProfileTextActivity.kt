package io.github.meshkati.ovpnvless

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText

/** A profile's .ovpn as text, full screen. Saved when the screen is left. */
class ProfileTextActivity : Activity() {

    private lateinit var profile: Profile
    private lateinit var text: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepOffScreenshots()
        setContentView(R.layout.activity_profile_text)
        edgeToEdge(findViewById(R.id.root))
        profile = Profiles.of(this) ?: return finish() // deleted meanwhile
        showProfileName(profile)
        text = findViewById(R.id.profile)
        // The text is not saved in the instance state (it can be large enough
        // to crash with TransactionTooLargeException); the profile holds it.
        text.setText(profile.ovpn)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
    }

    override fun onPause() {
        if (this::profile.isInitialized) profile.ovpn = text.text.toString()
        super.onPause()
    }
}
