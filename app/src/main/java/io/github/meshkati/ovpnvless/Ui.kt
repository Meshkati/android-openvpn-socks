package io.github.meshkati.ovpnvless

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/** A drawable from the theme, e.g. android.R.attr.selectableItemBackground. */
fun themeDrawable(context: Context, attr: Int): Drawable? {
    val a = context.obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getDrawable(0)
    } finally {
        a.recycle()
    }
}

/**
 * Draws the activity behind the status and navigation bars on every version
 * (Android 15+ does this anyway for targetSdk 35+), and pads [root] so its
 * content stays clear of the bars, the display cutout and the keyboard.
 */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge(root: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        val decor = window.decorView
        decor.systemUiVisibility = decor.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + max(bars.bottom, ime.bottom))
        } else {
            // Before API 30 the system window insets include the keyboard.
            v.setPadding(
                left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
    root.requestApplyInsets()
}
