package dev.velocity.browser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.widget.EditText
import android.widget.TextView
import dev.velocity.browser.R

/** The only color definitions are in colors.xml; explicit mode is independent of OS night resources. */
data class BrowserPalette(val dark: Boolean, val background: Int, val surface: Int, val card: Int,
    val text: Int, val muted: Int, val accent: Int, val border: Int, val pressed: Int, val danger: Int, val onAccent: Int, val privateAccent: Int) {
    companion object {
        fun resolve(c: Context, dark: Boolean): BrowserPalette {
            fun color(light: Int, night: Int) = c.getColor(if (dark) night else light)
            return BrowserPalette(dark, color(R.color.browser_light_background,R.color.browser_dark_background),
                color(R.color.browser_light_surface,R.color.browser_dark_surface), color(R.color.browser_light_card,R.color.browser_dark_card),
                color(R.color.browser_light_text,R.color.browser_dark_text), color(R.color.browser_light_muted,R.color.browser_dark_muted),
                color(R.color.browser_light_accent,R.color.browser_dark_accent), color(R.color.browser_light_border,R.color.browser_dark_border),
                color(R.color.browser_light_pressed,R.color.browser_dark_pressed), color(R.color.browser_light_danger,R.color.browser_dark_danger),
                color(R.color.browser_light_on_accent,R.color.browser_dark_on_accent), color(R.color.browser_light_private,R.color.browser_dark_private))
        }
    }
}
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.rounded(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1),it) }
}
fun TextView.applyTextPalette(p: BrowserPalette) {
    setTextColor(p.text); setHintTextColor(p.muted); highlightColor = p.pressed
    if (this is EditText) {
        backgroundTintList = ColorStateList.valueOf(p.accent)
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.mutate()?.setTint(p.accent)
    }
}
