package dev.velocity.browser.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import dev.velocity.browser.tabs.TabRecord

fun TabRecord.displayTitle() = title.ifBlank { if (url == "about:blank") "新しいタブ" else url }
fun TabRecord.displayLocation() = if (url == "about:blank") "空のページ" else if (extensionBase != null) "拡張機能のページ" else runCatching { java.net.URI(url).host }.getOrNull() ?: url

/** Dialog stays open while a close confirmation or capacity decision is pending. */
class TabSwitcherView(context: Context, private val appearance: ThemeController,
    private val select: (String) -> Boolean, private val create: (Boolean) -> Boolean,
    private val close: (String) -> Unit, private val cancel: (String) -> Unit,
    private val reading: () -> Unit) {
    private val dialog = Dialog(context).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(16),context.dp(16),context.dp(16),context.dp(16)) }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val count = TextView(context).apply { textSize = 22f }
    private val pending = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var cached: Any? = null
    private var snapshot = emptyList<TabRecord>()
    private var selected: String? = null
    private var pendingId: String? = null
    val isShowing get() = dialog.isShowing
    init {
        fun action(label: String, callback: () -> Unit) = Button(context).apply {
            text = label; isAllCaps = false; minWidth = context.dp(48); minHeight = context.dp(48); setOnClickListener { callback() }
        }
        header.addView(count,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(action("＋") { if (create(false)) dismiss() }.apply { contentDescription = "新しいタブ" })
        header.addView(action("完了",::dismiss))
        panel.addView(header); panel.addView(pending)
        panel.addView(ScrollView(context).apply { addView(rows) },LinearLayout.LayoutParams(-1,0,1f))
        val footer = LinearLayout(context)
        footer.addView(action("非公開タブ") { if (create(true)) dismiss() },LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(action("閲覧専用") { reading() },LinearLayout.LayoutParams(0,-2,1f))
        panel.addView(footer); dialog.setContentView(panel)
    }
    fun show() {
        dialog.show(); dialog.window?.apply {
            setGravity(Gravity.BOTTOM); setLayout(-1,(panel.context.resources.displayMetrics.heightPixels * .75).toInt())
        }; applyPalette()
    }
    fun dismiss() = dialog.dismiss()
    fun update(tabs: List<TabRecord>, selectedId: String?, waiting: String?) {
        snapshot = tabs; selected = selectedId; pendingId = waiting
        val key = tabs.map { listOf(it.id,it.title,it.url,it.privateMode,it.id == selectedId) } + listOf(waiting,appearance.dark())
        if (key == cached) return
        cached = key; rebuild()
    }
    fun applyPalette() { cached = null; rebuild() }
    private fun rebuild() {
        val c = panel.context; val p = appearance.palette()
        panel.background = c.rounded(p.background,24)
        count.text = "タブ · ${snapshot.size}"; count.setTextColor(p.text)
        if (snapshot.any { it.privateMode }) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        pending.removeAllViews()
        pendingId?.let { id ->
            pending.addView(TextView(c).apply { text = "ページを開けませんでした。再試行するか、タブを整理できます。"; textSize = 13f; setTextColor(p.text) })
            pending.addView(Button(c).apply { text = "表示要求をキャンセル"; setTextColor(p.accent); setOnClickListener { cancel(id) } })
        }
        rows.removeAllViews()
        for (tab in snapshot) {
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(12),c.dp(8),c.dp(8),c.dp(8))
                background = c.rounded(p.card,16,if (tab.id == selected) p.accent else p.border)
            }
            val label = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; minimumHeight = c.dp(48); gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; contentDescription = "『${tab.displayTitle()}』を選択"
                setOnClickListener { if (select(tab.id)) dismiss() }
            }
            label.addView(TextView(c).apply { text = tab.displayTitle(); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(p.text) })
            label.addView(TextView(c).apply { text = (if (tab.privateMode) "非公開 · " else "") + tab.displayLocation(); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(p.muted) })
            row.addView(label,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(Button(c).apply {
                text = "×"; textSize = 24f; setTextColor(p.danger); background = c.rounded(p.pressed,12,p.border)
                minWidth = 0; minimumWidth = 0; setPadding(0,0,0,0)
                contentDescription = "『${tab.displayTitle()}』を閉じる"; setOnClickListener { close(tab.id) }
            },LinearLayout.LayoutParams(c.dp(48),c.dp(48)))
            rows.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin = c.dp(8) })
        }
        fun tint(view: android.view.View) {
            if (view is Button && view.parent !== rows) view.setTextColor(p.accent)
            if (view is android.view.ViewGroup && view !== rows) for (n in 0 until view.childCount) tint(view.getChildAt(n))
        }
        tint(header); tint(panel.getChildAt(panel.childCount-1))
    }
}
