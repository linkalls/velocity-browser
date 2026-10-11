package dev.velocity.browser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import dev.velocity.browser.tabs.TabRecord
import dev.velocity.browser.tabs.TabState

object TabSwitcherView {
    fun show(context: Context, tabs: List<TabRecord>, selected: String?, select: (String) -> Unit,
        create: (Boolean) -> Unit, close: (String) -> Unit, reading: () -> Unit = {}) {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val dialog = Dialog(context).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable().apply { setColor(Color.rgb(247,249,250)); cornerRadius = dp(24).toFloat() }
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply { text = "タブ  ·  ${tabs.size}"; textSize = 23f; setTextColor(Color.rgb(25,34,43)); typeface = android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) }, LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(context).apply { text = "完了"; isAllCaps = false; setOnClickListener { dialog.dismiss() } })
        panel.addView(header)
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (tab in tabs) {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(10),dp(4),dp(10))
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); if (tab.id == selected) setStroke(dp(2),Color.rgb(0,122,102)) }
            }
            val label = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { dialog.dismiss(); select(tab.id) }; contentDescription = "${tab.title.ifBlank { tab.url }}を選択"; isFocusable = true }
            val state = when (tab.state) { TabState.COLD -> "休眠"; TabState.ACTIVE -> "表示中"; TabState.RESTORING -> "再表示中"; TabState.FAILED -> "読み込み失敗"; else -> "保持中" }
            label.addView(TextView(context).apply { text = tab.title.ifBlank { if (tab.url == "about:blank") "新しいタブ" else tab.url }; textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.rgb(25,34,43)) })
            label.addView(TextView(context).apply { text = (if (tab.privateMode) "プライベート · " else "") + (if (tab.readOnly) "閲覧専用 · " else "") + state; textSize = 11f; setTextColor(Color.rgb(93,105,115)); setPadding(0,dp(5),0,0) })
            row.addView(label,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(Button(context).apply { text = "×"; textSize = 24f; contentDescription = "このタブを閉じる"; background = null; minWidth = 0; minimumWidth = 0; setOnClickListener { dialog.dismiss(); close(tab.id) } },LinearLayout.LayoutParams(dp(48),dp(48)))
            rows.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })
        }
        panel.addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(-1,0,1f))
        val footer = LinearLayout(context)
        fun action(text: String, callback: () -> Unit) { footer.addView(Button(context).apply { this.text = text; textSize = 12f; isAllCaps = false; setOnClickListener { dialog.dismiss(); callback() } },LinearLayout.LayoutParams(0,dp(52),1f)) }
        action("＋ 新規") { create(false) }; action("非公開") { create(true) }; action("閲覧専用") { reading() }
        panel.addView(footer)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT,(context.resources.displayMetrics.heightPixels * .75).toInt())
            if (tabs.any { it.privateMode }) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,(context.resources.displayMetrics.heightPixels * .75).toInt())
    }
}
