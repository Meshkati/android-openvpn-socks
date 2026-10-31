package io.github.meshkati.ovpnvless

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One step of the route on the home screen: OpenVPN or VLESS. */
class HopView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    enum class State { IDLE, ADD, BUSY, OK, ERROR }

    val dot: View
    private val dotNumber: TextView
    private val dotIcon: ImageView
    private val label: TextView
    private val value: TextView
    private val meta: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        View.inflate(context, R.layout.view_hop, this)
        dot = findViewById(R.id.hop_dot)
        dotNumber = findViewById(R.id.hop_dot_number)
        dotIcon = findViewById(R.id.hop_dot_icon)
        label = findViewById(R.id.hop_label)
        value = findViewById(R.id.hop_value)
        meta = findViewById(R.id.hop_meta)
        val dp = resources.displayMetrics.density
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        minimumHeight = (64 * dp).toInt()
        background = themeDrawable(context, android.R.attr.selectableItemBackground)
        isClickable = true
        isFocusable = true
    }

    fun bind(step: Int, label: CharSequence, value: CharSequence, meta: CharSequence, state: State) {
        this.label.text = label
        this.value.text = value
        this.meta.text = meta
        dotNumber.text = step.toString()

        val (dotBg, icon) = when (state) {
            State.IDLE -> R.drawable.bg_dot_idle to 0
            State.ADD -> R.drawable.bg_dot_add to R.drawable.ic_plus
            State.BUSY -> R.drawable.bg_dot_busy to 0
            State.OK -> R.drawable.bg_dot_ok to R.drawable.ic_check
            State.ERROR -> R.drawable.bg_dot_err to 0
        }
        dot.setBackgroundResource(dotBg)
        dotNumber.visibility = if (icon == 0) VISIBLE else GONE
        dotIcon.visibility = if (icon == 0) GONE else VISIBLE
        if (icon != 0) {
            dotIcon.setImageResource(icon)
            dotIcon.setColorFilter(color(if (state == State.OK) R.color.on_accent else R.color.accent))
        }
        dotNumber.setTextColor(
            color(
                when (state) {
                    State.BUSY -> R.color.warn
                    State.ERROR -> R.color.err
                    else -> R.color.muted
                }
            )
        )
        this.value.setTextColor(color(if (state == State.ADD) R.color.accent else R.color.ink))
        this.meta.setTextColor(
            color(
                when (state) {
                    State.OK -> R.color.accent
                    State.BUSY -> R.color.warn
                    State.ERROR -> R.color.err
                    else -> R.color.muted
                }
            )
        )
        contentDescription = context.getString(R.string.hop_description, label, value, meta)
    }

    private fun color(id: Int) = context.getColor(id)
}
