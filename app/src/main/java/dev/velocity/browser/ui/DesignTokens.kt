package dev.velocity.browser.ui

import android.content.Context

object DesignTokens {
    const val TOUCH_DP = 48
    const val TEXT_SP = 16f
    const val SECONDARY_SP = 13f
    const val ANIMATION_MS = 150L
    fun toolbarHeight(context: Context) = context.dp(if (context.resources.configuration.fontScale >= 1.5f) 72 else 56)
}
