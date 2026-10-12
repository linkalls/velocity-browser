package dev.velocity.browser.ui

import android.content.Context

object DesignTokens {
    val SPACING_DP = intArrayOf(4,8,12,16,20,24,32)
    const val CONTROL_RADIUS = 12
    const val ADDRESS_RADIUS = 18
    const val CARD_RADIUS = 16
    const val SHEET_RADIUS = 24
    const val TITLE_SP = 22f
    const val TOUCH_DP = 48
    const val TEXT_SP = 16f
    const val SECONDARY_SP = 13f
    const val ANIMATION_MS = 150L
    fun toolbarHeight(context: Context) = context.dp(if (context.resources.configuration.fontScale >= 1.5f) 72 else 56)
}
