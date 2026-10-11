package dev.velocity.browser.ui
import android.app.AlertDialog
import android.content.Context
import dev.velocity.browser.R
import dev.velocity.browser.tabs.TabRecord

object TabSwitcherView {
    fun show(context: Context, tabs: List<TabRecord>, selected: String?, select: (String) -> Unit,
        create: (Boolean) -> Unit, close: (String) -> Unit) {
        val labels = tabs.map { t ->
            (if (t.id == selected) "● " else "") + (if (t.privateMode) context.getString(R.string.private_label) else "") +
                (if (t.protections.isNotEmpty() && t.live) context.getString(R.string.protected_label) else "") +
                "${t.title.ifBlank { t.url }} [${t.state}]"
        } + listOf(context.getString(R.string.new_tab), context.getString(R.string.private_tab)) +
            if (selected != null) listOf(context.getString(R.string.close_tab)) else emptyList()
        AlertDialog.Builder(context).setTitle(R.string.tabs).setItems(labels.toTypedArray()) { _, n ->
            when {
                n < tabs.size -> select(tabs[n].id)
                n == tabs.size -> create(false)
                n == tabs.size + 1 -> create(true)
                selected != null -> close(selected)
            }
        }.show()
    }
}
