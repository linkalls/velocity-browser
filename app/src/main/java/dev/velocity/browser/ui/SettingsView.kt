package dev.velocity.browser.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*

/** Searchable native settings. Values and explanations have separate visual hierarchy. */
class SettingsView(context: Context, private val appearance: ThemeController, title: String,
    rows: List<Row>, private val onDismiss: () -> Unit = {}) {
    data class Row(val category: String, val label: String, val detail: String = "", val action: () -> Unit) {
        var liveText: (() -> String)? = null
        var checked: (() -> Boolean)? = null
        var navigation: Boolean = true
    }
    private data class Binding(val row: Row, val root: LinearLayout, val label: TextView,
        val detail: TextView, val trailing: TextView?, val toggle: Switch?)
    private val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bindings = mutableListOf<Binding>()
    private val separators=mutableListOf<View>()
    private val headings = mutableMapOf<String, TextView>()
    private val heading = TextView(context).apply { text = title; textSize = 24f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val close = Button(context).apply { text = "閉じる"; isAllCaps = false; minHeight = context.dp(48); setOnClickListener { dialog.dismiss() } }
    private val search = EditText(context).apply {
        hint = "設定を検索"; textSize = 16f; setSingleLine(true); isSaveEnabled = false
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        contentDescription = "設定を検索"
    }
    private val empty = TextView(context).apply { text = "一致する設定がありません"; textSize = 16f; setPadding(context.dp(16), context.dp(24), context.dp(16), 0) }
    private val preferences=listOf("settings","appearance","block_settings").map {context.getSharedPreferences(it,Context.MODE_PRIVATE)}
    private val preferenceChanged=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,_->refresh()}
    val isShowing get() = dialog.isShowing
    private val themeChanged: () -> Unit = { applyPalette(); refresh() }
    init {
        panel.setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        panel.isFocusableInTouchMode = true
        panel.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close)
        })
        panel.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12); bottomMargin = context.dp(8) })
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // A category appears once, in the order supplied by the caller.
        rows.groupBy { it.category }.forEach { (category, items) ->
            val header = TextView(context).apply {
                text = category; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(context.dp(4), context.dp(20), 0, context.dp(8))
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }
            headings[category] = header; content.addView(header)
            items.forEach { row ->
                val root = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL; minimumHeight = context.dp(64)
                    setPadding(context.dp(16), context.dp(14), context.dp(12), context.dp(14))
                    isClickable = true; isFocusable = true
                }
                val label = TextView(context).apply { text = row.label; textSize = 16f }
                val detail = TextView(context).apply {
                    text = row.detail; textSize = 13f; setPadding(0, context.dp(4), 0, 0)
                    visibility = if (row.detail.isBlank()) View.GONE else View.VISIBLE
                }
                root.addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(detail) }, LinearLayout.LayoutParams(0, -2, 1f))
                val toggle = row.checked?.let { Switch(context).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } }
                val trailing = if (toggle == null && row.navigation) TextView(context).apply { text = "›"; textSize = 24f; setPadding(context.dp(12), 0, context.dp(4), 0) } else null
                if (toggle != null) root.addView(toggle) else if(trailing!=null)root.addView(trailing)
                val binding = Binding(row, root, label, detail, trailing, toggle); bindings += binding
                root.setOnClickListener { row.action(); refreshValues() }
                content.addView(root, LinearLayout.LayoutParams(-1, -2))
                content.addView(View(context).apply {setBackgroundColor(appearance.palette().border);separators+=this},LinearLayout.LayoutParams(-1,context.dp(1)))
            }
        }
        content.addView(empty)
        panel.addView(ScrollView(context).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.setContentView(panel)
        dialog.setOnDismissListener { appearance.remove(themeChanged);preferences.forEach {it.unregisterOnSharedPreferenceChangeListener(preferenceChanged)};onDismiss() }
    }
    fun refresh() { if(dialog.isShowing)refreshValues() }
    private fun refreshValues() {
        bindings.forEach { b ->
            val value = b.row.liveText?.invoke() ?: b.row.label
            val parts = value.split('\n', limit = 2)
            if (b.label.text.toString() != parts[0]) b.label.text = parts[0]
            val detail = parts.getOrElse(1) { b.row.detail }
            if (b.detail.text.toString() != detail) b.detail.text = detail
            b.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
            b.toggle?.isChecked = b.row.checked?.invoke() == true
            val description = parts[0] + if (b.toggle != null) if (b.toggle.isChecked) "、オン" else "、オフ" else ""
            if (b.root.contentDescription != description) b.root.contentDescription = description
        }
        filter()
    }
    private fun filter() {
        val query = search.text.toString().trim()
        bindings.forEach { b ->
            val matches = query.isEmpty() || "${b.row.category} ${b.label.text} ${b.detail.text}".contains(query, ignoreCase = true)
            val next = if (matches) View.VISIBLE else View.GONE
            if (b.root.visibility != next) b.root.visibility = next
        }
        headings.forEach { (category, header) -> header.visibility = if (bindings.any { it.row.category == category && it.root.visibility == View.VISIBLE }) View.VISIBLE else View.GONE }
        empty.visibility = if (bindings.none { it.root.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
    }
    fun show() {
        dialog.show(); dialog.window?.apply {
            setLayout(-1, -1); setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        appearance.track(dialog); appearance.observe(themeChanged);preferences.forEach {it.registerOnSharedPreferenceChangeListener(preferenceChanged)};applyPalette();refreshValues();panel.requestFocus()
    }
    private fun applyPalette() {
        val p = appearance.palette(); panel.setBackgroundColor(p.background); heading.setTextColor(p.text)
        close.setTextColor(p.accent); close.background = RippleDrawable(ColorStateList.valueOf(p.pressed), close.context.rounded(p.surface, 12), null)
        search.applyTextPalette(p); search.background = search.context.rounded(p.surface, 12)
        empty.setTextColor(p.muted); headings.values.forEach { it.setTextColor(p.muted) };separators.forEach {it.setBackgroundColor(p.border)}
        bindings.forEach { b ->
            b.root.background = RippleDrawable(ColorStateList.valueOf(p.pressed), b.root.context.rounded(p.background, 0), null)
            b.label.setTextColor(p.text); b.detail.setTextColor(p.muted); b.trailing?.setTextColor(p.muted)
            b.toggle?.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.muted))
        }
    }
}
