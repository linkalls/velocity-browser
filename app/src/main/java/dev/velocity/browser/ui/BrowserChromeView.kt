package dev.velocity.browser.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.velocity.browser.R
import org.mozilla.geckoview.GeckoView

/** ネイティブchromeのみ。ページ描画は1個のGeckoViewに集約。 */
class BrowserChromeView(context: Context) : LinearLayout(context) {
    private val ink = Color.rgb(25, 34, 43)
    private val muted = Color.rgb(109, 121, 131)
    private val accent = Color.rgb(0, 122, 102)
    private val surface = Color.rgb(247, 249, 250)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ripple(color: Int) = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 0, 122, 102)), rounded(color), null)
    val address = EditText(context).apply {
        hint = context.getString(R.string.address); setSingleLine(true); textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO; id = R.id.address; isSaveEnabled = false
        background = null; setTextColor(ink); setHintTextColor(muted); setPadding(dp(12), 0, dp(4), 0)
    }
    val title = TextView(context).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        textSize = 12f; setTextColor(muted); gravity = Gravity.END
    }
    val geckoView = GeckoView(context).apply { isSaveEnabled = false; isSaveFromParentEnabled = false }
    val back: Button; val forward: Button; val reload: Button
    private val tabsButton: ChromeButton
    private val shield = Button(context).apply {
        textSize = 12f; isAllCaps = false; setTextColor(accent); background = ripple(Color.rgb(230, 244, 239))
        minWidth = 0; minimumWidth = 0; minHeight = 0; setPadding(dp(12), 0, dp(12), 0)
        contentDescription = context.getString(R.string.block_settings)
    }
    private val start = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(16), dp(28), dp(24)); setBackgroundColor(surface) }
    private val privacy = TextView(context).apply { textSize = 11f; setTextColor(muted); setPadding(dp(8), 0, 0, 0) }
    var onNavigate: (String) -> Unit = {}
    var onTabs: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onBlock: () -> Unit = {}
    var onReading: () -> Unit = {}
    init {
        orientation = VERTICAL; setBackgroundColor(surface)
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        heading.addView(TextView(context).apply { text = "VELOCITY"; textSize = 13f; letterSpacing = .18f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(ink) })
        heading.addView(privacy)
        heading.addView(title, LayoutParams(0, dp(40), 1f)); addView(heading, LayoutParams(-1, dp(40)))
        val addressRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; background = rounded(Color.WHITE, 18); elevation = dp(1).toFloat(); setPadding(dp(4), dp(2), dp(2), dp(2)) }
        addressRow.addView(address, LayoutParams(0, dp(48), 1f))
        addressRow.addView(ChromeButton(context, 5).apply { setText(R.string.go); setOnClickListener { submit() } }, LayoutParams(dp(48), dp(48)))
        addView(addressRow, LayoutParams(-1, dp(52)).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        val content = FrameLayout(context)
        content.addView(geckoView, FrameLayout.LayoutParams(-1, -1)); content.addView(start, FrameLayout.LayoutParams(-1, -1))
        addView(content, LayoutParams(-1, 0, 1f))
        start.addView(TextView(context).apply { text = "Velocity"; textSize = 42f; letterSpacing = -.04f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(ink) })
        start.addView(TextView(context).apply { text = "軽やかに、次のページへ。"; textSize = 15f; setTextColor(muted); setPadding(0, dp(10), 0, dp(30)) })
        fun shortcut(label: String, action: () -> Unit) {
            start.addView(Button(context).apply { text = label; textSize = 15f; isAllCaps = false; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0); background = ripple(Color.WHITE); setOnClickListener { action() } }, LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        }
        shortcut("Wikipedia  ↗") { onNavigate("https://ja.wikipedia.org/") }
        shortcut("DuckDuckGo  ↗") { onNavigate("https://duckduckgo.com/") }
        shortcut("閲覧専用タブ  ↗") { onReading() }
        start.addView(TextView(context).apply { text = "入力のあるページは保持。\n閲覧専用の本文は、休眠後も再表示できます。"; textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        val divider = View(context).apply { setBackgroundColor(Color.rgb(230, 236, 237)) }; addView(divider, LayoutParams(-1, dp(1)))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4)) }
        fun button(kind: Int, label: Int) = ChromeButton(context, kind).apply { setText(label); actions.addView(this, LayoutParams(0, dp(52), 1f)) }
        back = button(0, R.string.back); forward = button(1, R.string.forward); reload = button(2, R.string.reload)
        tabsButton = button(3, R.string.tabs).apply { setOnClickListener { onTabs() } }
        button(4, R.string.settings).setOnClickListener { onSettings() }
        addView(actions, LayoutParams(-1, dp(60)))
        val statusRow = LinearLayout(context).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), dp(6)) }
        shield.setOnClickListener { onBlock() }; statusRow.addView(shield, LayoutParams(-2, dp(30)))
        addView(statusRow, LayoutParams(-1, dp(36)))
        address.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { submit(); true } else false }
        setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else { @Suppress("DEPRECATION") setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom) }
            insets
        }
    }
    fun showStart(show: Boolean) { start.visibility = if (show) View.VISIBLE else View.GONE }
    fun updateState(tabCount: Int, privateMode: Boolean, mode: String, lite: Boolean, busy: Boolean, decisions: Int, status: String) {
        tabsButton.count = tabCount; tabsButton.invalidate(); tabsButton.contentDescription = "タブ $tabCount 件"
        privacy.text = if (privateMode) "PRIVATE" else ""
        shield.text = when { busy -> "準備中…"; status.contains("FAILED") || status.contains("INVALID") || status == "DISCONNECTED" -> "拡張の状態を確認"; lite && !status.startsWith("READY_CONFIG_ACK") -> "Lite · 準備中…"; mode == "ADGUARD" -> "AdGuard · 設定"; lite -> "Lite · 遮断判定 $decisions"; mode == "ALL_OFF" -> "広告ブロック OFF"; else -> "追跡防止 · Lite OFF" }
    }
    private fun submit() {
        val value = address.text.toString(); address.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        onNavigate(value)
    }
    /** ベクター相当の小さなCanvasアイコン。ラベルはaccessibility用にButtonへ保持。 */
    private inner class ChromeButton(context: Context, private val kind: Int) : Button(context) {
        var count = 0
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
        init { background = ripple(Color.TRANSPARENT); minWidth = 0; minimumWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0); isAllCaps = false }
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(resources.displayMetrics.density, resources.displayMetrics.density)
            pen.alpha = if (isEnabled) 255 else 70; pen.style = Paint.Style.STROKE
            fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (n in 2 until points.size step 2) p.lineTo(points[n], points[n + 1]); canvas.drawPath(p, pen) }
            when (kind) {
                0 -> path(4f,-8f,-4f,0f,4f,8f)
                1 -> path(-4f,-8f,4f,0f,-4f,8f)
                2 -> if (text.toString() == context.getString(R.string.stop)) canvas.drawRoundRect(-6f,-6f,6f,6f,1f,1f,pen) else { canvas.drawArc(-8f,-8f,8f,8f,40f,285f,false,pen); path(8f,-8f,8f,-2f,2f,-2f) }
                3 -> { canvas.drawRoundRect(-9f,-9f,9f,9f,4f,4f,pen); pen.style = Paint.Style.FILL; pen.textSize = 10f; pen.textAlign = Paint.Align.CENTER; canvas.drawText(if (count > 99) "99+" else count.toString(),0f,3.5f,pen) }
                4 -> { pen.style = Paint.Style.FILL; for (x in listOf(-6f,0f,6f)) canvas.drawCircle(x,0f,1.7f,pen) }
                5 -> { canvas.drawLine(-7f,0f,7f,0f,pen); path(1f,-6f,7f,0f,1f,6f) }
            }
            canvas.restore()
        }
    }
}
