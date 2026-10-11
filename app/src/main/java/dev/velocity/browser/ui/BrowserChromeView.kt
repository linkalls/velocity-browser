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
    private var palette = BrowserPalette.resolve(context,false)
    private val ink get() = palette.text
    private val muted get() = palette.muted
    private val accent get() = palette.accent
    private val surface get() = palette.background
    private val nativeButtons = mutableListOf<Button>()
    private lateinit var addressRow: LinearLayout
    private lateinit var divider: View
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ripple(color: Int) = RippleDrawable(ColorStateList.valueOf(palette.pressed), rounded(color), null)
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
    private val shield = ChromeButton(context,6).apply { contentDescription = context.getString(R.string.block_settings) }
    private val start = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(16), dp(28), dp(24)); setBackgroundColor(surface) }
    private val privacy = TextView(context).apply { textSize = 11f; setTextColor(muted); setPadding(dp(8), 0, 0, 0) }
    var onNavigate: (String) -> Unit = {}
    var onTabs: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onBlock: () -> Unit = {}
    var onReading: () -> Unit = {}
    var onCreateTab: (Boolean) -> Unit = {}
    var onHome: () -> Unit = {}
    var onSwitchTab: (Int) -> Unit = {}
    private val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    var bottomAddress=preferences.getBoolean("address_bottom",false);private set
    var swipeEnabled:Boolean
        get()=preferences.getBoolean("address_swipe",true)
        set(value){preferences.edit().putBoolean("address_swipe",value).apply()}
    fun setAddressBottom(bottom:Boolean) {
        bottomAddress=bottom;preferences.edit().putBoolean("address_bottom",bottom).apply()
        removeView(addressRow);addView(addressRow,if(bottom)childCount-1 else 0)
    }
    init {
        orientation = VERTICAL; setBackgroundColor(surface)
        addressRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; background = rounded(palette.surface,18); setPadding(dp(4),dp(2),dp(2),dp(2)) }
        addressRow.addView(shield, LayoutParams(dp(48),dp(48)))
        shield.setOnClickListener { onBlock() }
        addressRow.addView(address, LayoutParams(0, dp(48), 1f))
        reload = ChromeButton(context,2).apply {setText(R.string.reload)}
        addressRow.addView(reload,LayoutParams(dp(48),dp(48)))
        addView(addressRow, LayoutParams(-1, dp(52)).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) })
        val content = FrameLayout(context)
        content.addView(geckoView, FrameLayout.LayoutParams(-1, -1)); content.addView(start, FrameLayout.LayoutParams(-1, -1))
        addView(content, LayoutParams(-1, 0, 1f))
        start.addView(TextView(context).apply { text = "Velocity"; textSize = 42f; letterSpacing = -.04f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(ink) })
        start.addView(TextView(context).apply { text = "軽やかに、次のページへ。"; textSize = 15f; setTextColor(muted); setPadding(0, dp(10), 0, dp(30)) })
        fun shortcut(label: String, action: () -> Unit) {
            start.addView(Button(context).apply { nativeButtons += this; text = label; textSize = 15f; isAllCaps = false; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0); background = ripple(Color.WHITE); setOnClickListener { action() } }, LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        }
        shortcut("Wikipedia  ↗") { onNavigate("https://ja.wikipedia.org/") }
        shortcut("DuckDuckGo  ↗") { onNavigate("https://duckduckgo.com/") }

        start.addView(TextView(context).apply { text = "次のページへ、すばやく。"; textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        divider = View(context).apply { setBackgroundColor(palette.border) }; addView(divider, LayoutParams(-1, dp(1)))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4)) }
        fun button(kind: Int, label: Int) = ChromeButton(context, kind).apply { setText(label); contentDescription = context.getString(label); actions.addView(this, LayoutParams(0, dp(52), 1f)) }
        back = button(0, R.string.back); forward = button(1, R.string.forward)
        button(7,R.string.app_name).apply {contentDescription="ホーム";setOnClickListener {onHome()}}
        tabsButton = button(3, R.string.tabs).apply { setOnClickListener { onTabs() } }
        button(8,R.string.go).apply {contentDescription="新しいタブ";setOnClickListener {onCreateTab(false)};setOnLongClickListener {onCreateTab(true);true}}
        button(4, R.string.settings).setOnClickListener { onSettings() }
        addView(LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0);addView(title,LayoutParams(0,-2,1f));addView(privacy,LayoutParams(-2,-2))},LayoutParams(-1,-2))
        addView(actions, LayoutParams(-1, dp(60)))
        if(bottomAddress)setAddressBottom(true)
        var touchX=0f;var touchY=0f;var wasEditing=false
        address.setOnTouchListener {_,event->
            when(event.actionMasked){
                android.view.MotionEvent.ACTION_DOWN->{touchX=event.x;touchY=event.y;wasEditing=address.hasFocus();false}
                android.view.MotionEvent.ACTION_UP->{
                    if(swipeEnabled && !wasEditing && kotlin.math.abs(event.x-touchX)>dp(100) && kotlin.math.abs(event.y-touchY)<dp(40)) {onSwitchTab(if(event.x>touchX)-1 else 1);true}else false
                }
                else->false
            }
        }
        address.setOnLongClickListener {
            if(address.hasFocus())return@setOnLongClickListener false
            android.widget.PopupMenu(context,address).apply {
                menu.add("URLをコピー").setOnMenuItemClickListener {(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("URL",address.text.toString()));true}
                menu.add("URLを共有").setOnMenuItemClickListener {context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {type="text/plain";putExtra(android.content.Intent.EXTRA_TEXT,address.text.toString())},"URLを共有"));true}
                menu.add("このサイトの設定").setOnMenuItemClickListener {onBlock();true}
            }.show();true
        }
        isFocusableInTouchMode=true;requestFocus()
        address.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { submit(); true } else false }
        setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else { @Suppress("DEPRECATION") setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom) }
            insets
        }
    }
    fun showStart(show: Boolean) { start.visibility = if (show) View.VISIBLE else View.GONE }
    fun updateState(tabCount: Int, privateMode: Boolean, mode: String, lite: Boolean, busy: Boolean, decisions: Int, status: String) {
        if (tabsButton.count != tabCount) { tabsButton.count = tabCount; tabsButton.invalidate() }
        val tabDescription = "タブ $tabCount 件"
        if (tabsButton.contentDescription != tabDescription) tabsButton.contentDescription = tabDescription
        val privacyLabel = if (privateMode) "PRIVATE" else ""
        if (privacy.text.toString() != privacyLabel) privacy.text = privacyLabel
        val shieldDescription = context.getString(R.string.block_settings) + " · " + when { busy -> "準備中…"; status.contains("FAILED") || status.contains("INVALID") || status == "DISCONNECTED" -> "拡張の状態を確認"; lite && !status.startsWith("READY_CONFIG_ACK") -> "Lite · 準備中…"; mode == "ADGUARD" && privateMode -> "非公開 · AdGuard OFF"; mode == "ADGUARD" -> "AdGuard · 設定"; lite -> "Lite · 遮断判定 $decisions"; mode == "ALL_OFF" -> "広告ブロック OFF"; else -> "追跡防止 · Lite OFF" }
        if (shield.contentDescription != shieldDescription) shield.contentDescription = shieldDescription
    }
    fun applyPalette(next: BrowserPalette) {
        palette = next; setBackgroundColor(surface); start.setBackgroundColor(surface)
        addressRow.background = rounded(next.surface,18); divider.setBackgroundColor(next.border)
        address.applyTextPalette(next); title.setTextColor(muted); privacy.setTextColor(muted)
        for (n in 0 until start.childCount) {
            val child = start.getChildAt(n)
            if (child is TextView) child.setTextColor(if (n == 0 || child is Button) ink else muted)
        }
        nativeButtons.forEach { it.background = ripple(next.surface) }
        fun visit(v: View) {
            if (v is ChromeButton) { v.background = ripple(Color.TRANSPARENT); v.invalidate() }
            if (v is android.view.ViewGroup && v !== geckoView) for (n in 0 until v.childCount) visit(v.getChildAt(n))
        }
        visit(this)
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
            pen.color = if (kind == 6) accent else ink
            pen.alpha = if (isEnabled) 255 else 70; pen.style = Paint.Style.STROKE
            fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (n in 2 until points.size step 2) p.lineTo(points[n], points[n + 1]); canvas.drawPath(p, pen) }
            when (kind) {
                0 -> path(4f,-8f,-4f,0f,4f,8f)
                1 -> path(-4f,-8f,4f,0f,-4f,8f)
                2 -> if (text.toString() == context.getString(R.string.stop)) canvas.drawRoundRect(-6f,-6f,6f,6f,1f,1f,pen) else { canvas.drawArc(-8f,-8f,8f,8f,40f,285f,false,pen); path(8f,-8f,8f,-2f,2f,-2f) }
                3 -> { canvas.drawRoundRect(-9f,-9f,9f,9f,4f,4f,pen); pen.style = Paint.Style.FILL; pen.textSize = 10f; pen.textAlign = Paint.Align.CENTER; canvas.drawText(if (count > 99) "99+" else count.toString(),0f,3.5f,pen) }
                4 -> { pen.style = Paint.Style.FILL; for (x in listOf(-6f,0f,6f)) canvas.drawCircle(x,0f,1.7f,pen) }
                7 -> {path(-9f,0f,0f,-8f,9f,0f);path(-6f,-2f,-6f,8f,6f,8f,6f,-2f)}
                8 -> {canvas.drawLine(-8f,0f,8f,0f,pen);canvas.drawLine(0f,-8f,0f,8f,pen)}
                6 -> path(0f,-10f,8f,-6f,7f,3f,0f,10f,-7f,3f,-8f,-6f,0f,-10f)
                5 -> { canvas.drawLine(-7f,0f,7f,0f,pen); path(1f,-6f,7f,0f,1f,6f) }
            }
            canvas.restore()
        }
    }
}
