package dev.velocity.browser.ui

import android.app.Activity
import android.widget.*

/** Debug-only entry point, deterministic palette screenshot fixture. */
object PalettePreview {
    fun show(activity: Activity, appearance: ThemeController) {
        val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(16),activity.dp(8),activity.dp(16),activity.dp(8))}
        for(dark in listOf(false,true)){
            val p=BrowserPalette.resolve(activity,dark)
            content.addView(activity.sectionHeader(if(dark)"Dark" else "Light").apply {setTextColor(appearance.palette().text)})
            for((label,color) in listOf("Background" to p.background,"Surface" to p.surface,"Subtle" to p.card,"Text" to p.text,"Secondary" to p.muted,"Accent" to p.accent,"On Accent" to p.onAccent,"Border" to p.border,"Selected" to p.pressed,"Danger" to p.danger,"Private" to p.privateAccent)) {
                content.addView(TextView(activity).apply {
                    text=label+"  #"+Integer.toHexString(color).takeLast(6).uppercase();textSize=16f
                    setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8));setBackgroundColor(color)
                    val brightness=android.graphics.Color.red(color)*.2126+android.graphics.Color.green(color)*.7152+android.graphics.Color.blue(color)*.0722
                    setTextColor(if(brightness>140)android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                })
            }
        }
        appearance.show(appearance.builder(activity).setTitle("配色プレビュー").setView(ScrollView(activity).apply {addView(content)}).setPositiveButton("閉じる",null))
        // Reapply explicit swatches after the shared dialog theme traversal.
        var index=0
        for(dark in listOf(false,true)){
            val p=BrowserPalette.resolve(activity,dark);index++
            for(color in listOf(p.background,p.surface,p.card,p.text,p.muted,p.accent,p.onAccent,p.border,p.pressed,p.danger,p.privateAccent)){
                val view=content.getChildAt(index++) as TextView;view.setBackgroundColor(color)
                val brightness=android.graphics.Color.red(color)*.2126+android.graphics.Color.green(color)*.7152+android.graphics.Color.blue(color)*.0722
                view.setTextColor(if(brightness>140)android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}
