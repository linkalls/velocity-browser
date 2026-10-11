package dev.velocity.browser.ui
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.velocity.browser.R
import org.mozilla.geckoview.GeckoView

class BrowserChromeView(context: Context) : LinearLayout(context) {
    val address = EditText(context).apply {
        hint = context.getString(R.string.address); setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        id = R.id.address
        isSaveEnabled = false // private URL/未送信検索語をActivity stateへ保存しない。
    }
    val title = TextView(context).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
    val geckoView = GeckoView(context).apply { isSaveEnabled = false; isSaveFromParentEnabled = false }
    val back: Button; val forward: Button; val reload: Button
    var onNavigate: (String) -> Unit = {}
    var onTabs: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onBlock: () -> Unit = {}
    init {
        orientation = VERTICAL
        val row = LinearLayout(context)
        row.addView(Button(context).apply { text = "🛡"; contentDescription = context.getString(R.string.block_settings); setOnClickListener { onBlock() } }, LayoutParams((48 * resources.displayMetrics.density).toInt(), LayoutParams.WRAP_CONTENT))
        row.addView(address, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(context).apply { setText(R.string.go); setOnClickListener { submit() } })
        addView(row); addView(title)
        val actions = LinearLayout(context)
        fun button(text: Int) = Button(context).apply { setText(text); actions.addView(this) }
        back = button(R.string.back); forward = button(R.string.forward); reload = button(R.string.reload)
        button(R.string.tabs).setOnClickListener { onTabs() }
        button(R.string.settings).setOnClickListener { onSettings() }
        addView(HorizontalScrollView(context).apply { addView(actions) })
        addView(geckoView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        address.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { submit(); true } else false }
        setOnApplyWindowInsetsListener { _, insets ->
            setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets
        }
    }
    private fun submit() {
        val value = address.text.toString(); address.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        onNavigate(value)
    }
}
