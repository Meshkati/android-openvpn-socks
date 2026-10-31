package io.github.meshkati.ovpnvless

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import kotlin.math.min

/** The round connect/disconnect button on the home screen. */
class PowerToggle(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Mode { OFF, BUSY, ON }

    var mode = Mode.OFF
        set(value) {
            if (field == value) return
            field = value
            updateSpinner()
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var spin = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spin = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        foreground = themeDrawable(context, android.R.attr.selectableItemBackgroundBorderless)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpinner()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateSpinner()
    }

    private fun updateSpinner() {
        val run = mode == Mode.BUSY && isAttachedToWindow && isShown
        if (run && !spinner.isStarted) spinner.start()
        if (!run && spinner.isStarted) spinner.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val ringWidth = r * 0.14f
        val coreR = r - ringWidth

        // Ring
        fill.color = color(if (mode == Mode.ON) R.color.accent_soft else R.color.track)
        canvas.drawCircle(cx, cy, r, fill)
        if (mode == Mode.BUSY) {
            stroke.color = color(R.color.warn)
            stroke.strokeWidth = ringWidth
            stroke.strokeCap = Paint.Cap.BUTT
            val inset = ringWidth / 2
            oval.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
            canvas.drawArc(oval, spin - 90f, 115f, false, stroke)
        }

        // Core with a soft edge below it
        fill.color = color(R.color.shadow)
        canvas.drawCircle(cx, cy + 1.5f * dp, coreR + 0.5f * dp, fill)
        fill.color = color(if (mode == Mode.ON) R.color.accent else R.color.card)
        canvas.drawCircle(cx, cy, coreR, fill)

        // Power symbol: an open circle with a bar through the gap
        val iconR = coreR * 0.34f
        stroke.color = color(
            when (mode) {
                Mode.ON -> R.color.on_accent
                Mode.BUSY -> R.color.warn
                Mode.OFF -> R.color.muted
            }
        )
        stroke.strokeWidth = coreR * 0.075f
        stroke.strokeCap = Paint.Cap.ROUND
        val iy = cy + iconR * 0.12f
        oval.set(cx - iconR, iy - iconR, cx + iconR, iy + iconR)
        canvas.drawArc(oval, -60f, 300f, false, stroke)
        canvas.drawLine(cx, iy - iconR * 1.18f, cx, iy - iconR * 0.15f, stroke)
    }

    private fun color(id: Int) = context.getColor(id)
}
