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
        panel.addView(TextView(c).apply { text = "セッションを作成できませんでした。元のページと入力は保持しています。メモリ不足やエンジンの状態を確認してください。保護中のページは自動で閉じません。"; textSize = 14f; setTextColor(p.muted) })
        panel.addView(Button(c).apply { text = "再試行"; setTextColor(p.accent); setOnClickListener { dismiss(); close(targetId) } })
        panel.addView(Button(c).apply { text = "タブ一覧を開く"; setTextColor(p.accent); setOnClickListener { dismiss(); organize() } })
        panel.addView(Button(c).apply { text = "キャンセル"; setTextColor(p.accent); setOnClickListener { cancel(targetId); dismiss() } })
        if (tabs.any { it.privateMode }) dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        appearance.tintDialog(dialog)
        // tintDialog styles standard controls; destructive row buttons retain their semantic color.
        
    }
}
