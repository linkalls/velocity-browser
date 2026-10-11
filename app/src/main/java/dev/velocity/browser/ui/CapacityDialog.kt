package dev.velocity.browser.ui

import android.app.Activity
import android.app.Dialog
import android.widget.*
import dev.velocity.browser.tabs.TabRecord

/** All destructive choices still pass through the coordinator's protected-close warning. */
class CapacityDialog(activity: Activity, val targetId: String, private val adguard: Boolean,
    private val appearance: ThemeController, private val close: (String) -> Unit,
    private val cancel: (String) -> Unit, private val organize: () -> Unit) {
    private val dialog = Dialog(appearance.dialogContext(activity))
    private val panel = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(20),context.dp(20),context.dp(20),context.dp(20)) }
    private var programmatic = false
    private var tabs = emptyList<TabRecord>()
    init {
        dialog.setContentView(panel)
        dialog.setOnCancelListener { if (!programmatic) cancel(targetId) }
    }
    fun show() { dialog.show(); dialog.window?.setLayout(-1,-2); update(tabs) }
    fun dismiss() { programmatic = true; dialog.dismiss() }
    fun update(current: List<TabRecord>) {
        tabs = current; val p = appearance.palette(); val c = panel.context
        panel.background = c.rounded(p.surface,20); panel.removeAllViews()
        panel.addView(TextView(c).apply { text = if (adguard) "AdGuardの詳細設定を開く" else "タブを表示する"; textSize = 20f; setTextColor(p.text) })
        panel.addView(TextView(c).apply { text = "ライブタブは最大2枚です。保持中のタブを1枚選んで閉じると、保留中のページを自動で開きます。"; textSize = 14f; setTextColor(p.muted) })
        tabs.filter { it.live }.forEach { t ->
            panel.addView(Button(c).apply {
                text = "${t.displayTitle()}\n${t.displayLocation()} を閉じる"; isAllCaps = false; minHeight = c.dp(48)
                setTextColor(p.danger); background = c.rounded(p.card,12,p.border)
                contentDescription = "『${t.displayTitle()}』を閉じて表示"
                setOnClickListener { close(t.id) }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin = c.dp(8) })
        }
        panel.addView(Button(c).apply { text = "タブ一覧を開く"; setTextColor(p.accent); setOnClickListener { dismiss(); organize() } })
        panel.addView(Button(c).apply { text = "キャンセル"; setTextColor(p.accent); setOnClickListener { cancel(targetId); dismiss() } })
        if (tabs.any { it.privateMode }) dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        appearance.tintDialog(dialog)
        // tintDialog styles standard controls; destructive row buttons retain their semantic color.
        for (i in 2 until panel.childCount-2) (panel.getChildAt(i) as? Button)?.setTextColor(p.danger)
    }
}
