package io.github.meshkati.ovpnvless

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

/**
 * Profiles: every saved profile with its route. Tap one to open it
 * ([ProfileActivity]); its menu renames, duplicates, moves or deletes it.
 * A delete can be undone for a few seconds.
 */
class ProfilesActivity : Activity() {

    private companion object {
        const val UNDO_MILLIS = 6000L

        const val MENU_RENAME = 1
        const val MENU_DUPLICATE = 2
        const val MENU_UP = 3
        const val MENU_DOWN = 4
        const val MENU_DELETE = 5
    }

    private lateinit var list: LinearLayout
    private lateinit var undoBar: View
    private lateinit var undoText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val endUndo = Runnable { forgetDeleted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        edgeToEdge(findViewById(R.id.root))
        list = findViewById(R.id.profile_list)
        list.clipToOutline = true // keeps row ripples inside the card's rounded corners
        undoBar = findViewById(R.id.undo_bar)
        undoText = findViewById(R.id.undo_text)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.new_profile).setOnClickListener { open(Profiles.create(this), new = true) }
        findViewById<View>(R.id.undo).setOnClickListener {
            handler.removeCallbacks(endUndo)
            Profiles.undo(this)
            undoBar.visibility = View.GONE
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        render()
        // A delete from the profile's own screen is undone here.
        Profiles.pending?.let(::showUndo)
    }

    override fun onStop() {
        // A rotation keeps the Undo: onStart shows it again.
        if (!isChangingConfigurations) forgetDeleted()
        Widgets.refresh(this) // the profile in use or the number of profiles may have changed
        super.onStop()
    }

    private fun render() {
        list.removeAllViews()
        val profiles = Profiles.all(this)
        val activeId = Profiles.activeId(this)
        for ((index, profile) in profiles.withIndex()) {
            val row = ProfileRow(layoutInflater.inflate(R.layout.view_profile_row, list, false))
            val name = profile.displayName(this)
            row.bind(name, profileRoute(this, profile), R.color.muted, selected = false, inUse = profile.id == activeId)
            row.view.setOnClickListener { open(profile, new = false) }
            row.more.visibility = View.VISIBLE
            row.more.contentDescription = getString(R.string.profiles_options, name)
            row.more.setOnClickListener { showMenu(it, profile, index, profiles.size) }
            if (index > 0) list.addView(divider())
            list.addView(row.view)
        }
    }

    private fun divider() = View(this).apply {
        val dp = resources.displayMetrics.density
        setBackgroundColor(getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp.toInt().coerceAtLeast(1))
            .apply { marginStart = (68 * dp).toInt(); marginEnd = (16 * dp).toInt() }
    }

    private fun showMenu(anchor: View, profile: Profile, index: Int, count: Int) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_RENAME, 0, R.string.profiles_rename)
        menu.menu.add(0, MENU_DUPLICATE, 1, R.string.profiles_duplicate)
        if (index > 0) menu.menu.add(0, MENU_UP, 2, R.string.profiles_move_up)
        if (index < count - 1) menu.menu.add(0, MENU_DOWN, 3, R.string.profiles_move_down)
        // The only profile stays; the one a session is using can't go until it ends.
        if (count > 1) {
            menu.menu.add(0, MENU_DELETE, 4, R.string.profiles_delete).isEnabled = !inUseBySession(profile)
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> rename(profile)
                MENU_DUPLICATE -> open(Profiles.create(this, copyOf = profile), new = false)
                MENU_UP, MENU_DOWN -> {
                    Profiles.move(this, profile.id, if (item.itemId == MENU_UP) -1 else 1)
                    render()
                }
                MENU_DELETE -> Profiles.delete(this, profile.id)?.let {
                    render()
                    showUndo(it)
                }
            }
            true
        }
        menu.show()
    }

    private fun inUseBySession(profile: Profile) =
        profile.id == Profiles.activeId(this) && VpnState.phase != VpnState.Phase.DISCONNECTED

    private fun rename(profile: Profile) {
        val dp = resources.displayMetrics.density
        val field = EditText(this).apply {
            setText(profile.name)
            setSelection(text.length)
            // Empty: named after the OpenVPN server again.
            hint = ProfileNames.display("", ProfileInfo.parse(profile.ovpn)?.host) ?: getString(R.string.profiles_unnamed)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.profiles_rename)
            .setView(box)
            .setPositiveButton(R.string.profiles_save) { _, _ ->
                profile.name = field.text.toString().trim()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private fun open(profile: Profile, new: Boolean) {
        startActivity(
            Intent(this, ProfileActivity::class.java)
                .putExtra(Profiles.EXTRA_PROFILE, profile.id)
                .putExtra(ProfileActivity.EXTRA_NEW, new)
        )
    }

    private fun showUndo(deleted: Profiles.Deleted) {
        undoText.text = getString(R.string.profiles_deleted, deleted.name)
        undoBar.visibility = View.VISIBLE
        handler.removeCallbacks(endUndo)
        handler.postDelayed(endUndo, UNDO_MILLIS)
    }

    private fun forgetDeleted() {
        handler.removeCallbacks(endUndo)
        Profiles.forget(this)
        undoBar.visibility = View.GONE
    }
}
