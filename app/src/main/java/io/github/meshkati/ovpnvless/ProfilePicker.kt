package io.github.meshkati.ovpnvless

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The home screen's profile picker: a bottom sheet with every profile and its
 * route. Tapping one calls [onPick] with its id and closes the sheet. Below,
 * New profile and Manage profiles. While a session runs, a note says that
 * picking another profile reconnects with it ([switchProfile]).
 */
class ProfilePicker(private val activity: Activity, private val onPick: (Long) -> Unit) {

    @Suppress("DEPRECATION") // setDecorFitsSystemWindows: still needed before Android 15
    fun show() {
        val dialog = Dialog(activity, R.style.Sheet)
        val sheet = activity.layoutInflater.inflate(R.layout.sheet_profiles, null)
        val rows = sheet.findViewById<LinearLayout>(R.id.sheet_rows)
        val note = sheet.findViewById<TextView>(R.id.sheet_note)

        val activeId = Profiles.activeId(activity)
        val phase = VpnState.phase
        for (profile in Profiles.all(activity)) {
            val row = ProfileRow(activity.layoutInflater.inflate(R.layout.view_profile_row, rows, false))
            val inUse = profile.id == activeId
            val (meta, color) = when {
                inUse && phase == VpnState.Phase.CONNECTED -> VpnState.tunnelAddress.let {
                    if (it.isEmpty()) activity.getString(R.string.hop_connected)
                    else activity.getString(R.string.hop_connected_address, it)
                } to R.color.accent
                inUse && phase == VpnState.Phase.CONNECTING -> activity.getString(R.string.hop_connecting) to R.color.warn
                else -> profileRoute(activity, profile) to R.color.muted
            }
            row.bind(profile.displayName(activity), meta, color, selected = inUse, inUse = false)
            row.view.setBackgroundResource(R.drawable.bg_row_pick)
            row.view.setOnClickListener {
                dialog.dismiss()
                onPick(profile.id)
            }
            rows.addView(row.view)
        }

        val running = phase == VpnState.Phase.CONNECTED || phase == VpnState.Phase.CONNECTING
        note.visibility = if (running && Profiles.ids(activity).size > 1) View.VISIBLE else View.GONE
        if (running) {
            note.text = activity.getString(
                if (phase == VpnState.Phase.CONNECTED) R.string.profiles_note_connected else R.string.profiles_note_connecting,
                Profiles.active(activity).displayName(activity),
            )
        }

        sheet.findViewById<View>(R.id.sheet_new).setOnClickListener {
            dialog.dismiss()
            val profile = Profiles.create(activity)
            activity.startActivity(
                Intent(activity, ProfileActivity::class.java)
                    .putExtra(Profiles.EXTRA_PROFILE, profile.id)
                    .putExtra(ProfileActivity.EXTRA_NEW, true)
            )
        }
        sheet.findViewById<View>(R.id.sheet_manage).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, ProfilesActivity::class.java))
        }

        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            if (Build.VERSION.SDK_INT >= 30) {
                // The sheet reaches the bottom edge, behind the navigation bar,
                // and pads its content above it.
                window.setDecorFitsSystemWindows(false)
                window.attributes = window.attributes.apply {
                    fitInsetsTypes = 0
                }
                val bottom = sheet.paddingBottom
                sheet.setOnApplyWindowInsetsListener { v, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.navigationBars())
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom + bars.bottom)
                    insets
                }
            }
        }
        dialog.show()
        // Some versions size a dialog from its theme on show(); full width again.
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
