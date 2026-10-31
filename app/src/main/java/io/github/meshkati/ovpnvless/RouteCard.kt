package io.github.meshkati.ovpnvless

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.LinearLayout

/** The route card: [HopView]s joined by a dotted line between their dots. */
class RouteCard(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.1f, 4 * dp), 0f)
        color = context.getColor(R.color.line)
    }

    init {
        orientation = VERTICAL
        clipToOutline = true // keeps row ripples inside the rounded corners
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val hops = (0 until childCount).map { getChildAt(it) }
            .filterIsInstance<HopView>().filter { it.visibility == VISIBLE }
        path.reset()
        for ((a, b) in hops.zipWithNext()) {
            val x = a.left + a.dot.left + a.dot.width / 2f
            path.moveTo(x, a.top + a.dot.bottom + 4 * dp)
            path.lineTo(x, b.top + b.dot.top - 4 * dp)
        }
        canvas.drawPath(path, paint)
    }
}
