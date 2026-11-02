package io.github.meshkati.ovpnvless

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * A profile's badge: the first letter of its name on a rounded square. The
 * selected profile's badge shows a check instead, so it doesn't rely on colour.
 */
class ProfileBadge(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val letter = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val check = ImageView(context).apply { setImageResource(R.drawable.ic_check) }

    init {
        val dp = resources.displayMetrics.density
        addView(letter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(check, LayoutParams((16 * dp).toInt(), (16 * dp).toInt(), Gravity.CENTER))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        bind("", selected = false)
    }

    fun bind(name: String, selected: Boolean) {
        letter.text = ProfileNames.initial(name)
        letter.setTextColor(context.getColor(R.color.accent))
        letter.visibility = if (selected) GONE else VISIBLE
        check.visibility = if (selected) VISIBLE else GONE
        check.setColorFilter(context.getColor(R.color.on_accent))
        setBackgroundResource(if (selected) R.drawable.bg_profile_badge_on else R.drawable.bg_profile_badge)
    }
}

/**
 * One profile in the picker ([ProfilePicker]) or the Profiles screen
 * ([ProfilesActivity]): badge, name, route, and on the Profiles screen an
 * "In use" label and a menu button. Inflated from view_profile_row.
 */
class ProfileRow(val view: View) {
    private val badge: ProfileBadge = view.findViewById(R.id.row_badge)
    private val name: TextView = view.findViewById(R.id.row_name)
    private val inUse: View = view.findViewById(R.id.row_in_use)
    private val meta: TextView = view.findViewById(R.id.row_meta)
    val more: View = view.findViewById(R.id.row_more)

    /**
     * [selected]: the picker's profile in use, with a check and a tinted
     * background. [inUse]: the Profiles screen's label for it.
     */
    fun bind(name: String, meta: CharSequence, metaColor: Int, selected: Boolean, inUse: Boolean) {
        val context = view.context
        badge.bind(name, selected)
        this.name.text = name
        this.meta.text = meta
        this.meta.setTextColor(context.getColor(metaColor))
        this.inUse.visibility = if (inUse) View.VISIBLE else View.GONE
        view.isSelected = selected
        view.contentDescription = listOfNotNull(
            name,
            context.getString(R.string.profiles_in_use).takeIf { selected || inUse },
            meta,
        ).joinToString(", ")
    }
}
