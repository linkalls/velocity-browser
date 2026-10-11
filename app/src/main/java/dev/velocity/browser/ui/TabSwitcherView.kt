package dev.velocity.browser.ui

import android.app.Dialog
import android.content.Context
import android.view.*
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import dev.velocity.browser.tabs.*

fun TabRecord.displayTitle() = title.ifBlank { if (url == "about:blank") "新しいタブ" else url }
fun TabRecord.displayLocation() = if (url == "about:blank") "空のページ" else if (extensionBase != null) "拡張機能のページ" else runCatching { java.net.URI(url).host }.getOrNull() ?: url

/** Native recycling grid; no persistent page thumbnails or external search. */
class TabSwitcherView(context: Context, private val appearance: ThemeController,
    private val select: (String) -> Boolean, private val create: (Boolean) -> Boolean,
    private val close: (String) -> Unit, private val cancel: (String) -> Unit,
    private val reading: () -> Unit, private val undo: () -> Unit = {},
    private val canUndo: () -> Boolean = { false }, private val pin: (String) -> Unit = {}) {
    private val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    private val dialog=Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(12),context.dp(12),context.dp(12),context.dp(12)) }
    private val count=TextView(context).apply { textSize=22f }
    private val search=EditText(context).apply { hint="タブを検索";contentDescription="タブを検索";isSingleLine=true;isSaveEnabled=false;minimumHeight=context.dp(48) }
    private val grid=GridView(context).apply { verticalSpacing=context.dp(8);horizontalSpacing=context.dp(8);stretchMode=GridView.STRETCH_COLUMN_WIDTH }
    private val undoButton=Button(context).apply { text="閉じるを取り消す（ページを再読み込み）";isAllCaps=false;minHeight=context.dp(48);setOnClickListener { undo();filter() } }
    private var snapshot=emptyList<TabRecord>();private var shown=emptyList<TabRecord>();private var selected:String?=null
    private var privateMode=false
    private var columns=if(preferences.getBoolean("tabs_list",false)) 1 else 2
    private val adapter=object: BaseAdapter() {
        override fun getCount()=shown.size
        override fun getItem(position:Int)=shown[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val row=(convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL;setPadding(context.dp(12),context.dp(8),context.dp(12),context.dp(8))
                addView(TextView(context).apply { textSize=16f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=context.dp(48) })
                addView(TextView(context).apply { textSize=12f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END })
                addView(LinearLayout(context).apply {
                    addView(Button(context).apply { text="固定";isAllCaps=false;minHeight=context.dp(48) },LinearLayout.LayoutParams(0,-2,1f))
                    addView(Button(context).apply { text="×";textSize=24f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0) },LinearLayout.LayoutParams(context.dp(48),context.dp(48)))
                })
            }
            val tab=shown[position];val p=appearance.palette()
            row.background=context.rounded(p.card,16,if(tab.id==selected)p.accent else p.border)
            (row.getChildAt(0) as TextView).apply { text=(if(tab.pinned)"• " else "")+tab.displayTitle();setTextColor(p.text) }
            (row.getChildAt(1) as TextView).apply {
                text=tab.displayLocation()+"\n"+when {tab.id==selected->"使用中";tab.live->"保持中";else->"休眠中 · 再読み込みされます"};setTextColor(p.muted)
            }
            val actions=row.getChildAt(2) as LinearLayout
            (actions.getChildAt(0) as Button).apply { text=if(tab.pinned)"固定解除" else "固定";setTextColor(p.accent);setOnClickListener { pin(tab.id) } }
            (actions.getChildAt(1) as Button).apply { setTextColor(p.danger);contentDescription="『${tab.displayTitle()}』を閉じる";setOnClickListener { close(tab.id) } }
            row.isFocusable=true;row.contentDescription="『${tab.displayTitle()}』を選択";row.setOnClickListener { if(select(tab.id)) dismiss() }
            var x=0f;var y=0f
            row.setOnTouchListener { _,event -> when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{x=event.x;y=event.y;false}
                MotionEvent.ACTION_UP->{if(kotlin.math.abs(event.x-x)>context.dp(100) && kotlin.math.abs(event.y-y)<context.dp(40)) {close(tab.id);true}else false}
                else->false
            } }
            return row
        }
    }
    val isShowing get()=dialog.isShowing
    init {
        fun button(label:String,action:()->Unit)=Button(context).apply { text=label;isAllCaps=false;minHeight=context.dp(48);setOnClickListener {action()} }
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        header.addView(count,LinearLayout.LayoutParams(0,-2,1f));header.addView(button("＋") {if(create(privateMode))dismiss()}.apply {contentDescription="新しいタブ"})
        header.addView(button("完了",::dismiss));panel.addView(header);panel.addView(search)
        panel.addView(LinearLayout(context).apply {
            addView(button("通常") {privateMode=false;filter()},LinearLayout.LayoutParams(0,-2,1f))
            addView(button("非公開タブ") {privateMode=true;filter()},LinearLayout.LayoutParams(0,-2,1f))
            addView(button("表示切替") {columns=if(columns==2)1 else 2;preferences.edit().putBoolean("tabs_list",columns==1).apply();grid.numColumns=columns;adapter.notifyDataSetChanged()},LinearLayout.LayoutParams(0,-2,1f))
        })
        grid.numColumns=columns;grid.adapter=adapter;panel.addView(grid,LinearLayout.LayoutParams(-1,0,1f));panel.addView(undoButton)
        search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()};override fun afterTextChanged(s:Editable?){} })
        dialog.setContentView(panel)
    }
    fun show() {dialog.show();dialog.window?.apply {setGravity(Gravity.BOTTOM);setLayout(-1,(panel.context.resources.displayMetrics.heightPixels*.90).toInt());setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)};applyPalette()}
    fun dismiss()=dialog.dismiss()
    fun update(tabs:List<TabRecord>,selectedId:String?,waiting:String?) {snapshot=tabs;selected=selectedId;if(!isShowing)privateMode=tabs.find {it.id==selectedId}?.privateMode==true;filter()}
    private fun filter() {
        val query=search.text.toString().trim();val old=grid.firstVisiblePosition
        shown=snapshot.filter {it.privateMode==privateMode && (query.isEmpty() || it.displayTitle().contains(query,true) || it.displayLocation().contains(query,true))}
        count.text=(if(privateMode)"非公開" else "タブ")+" ${shown.size}枚";adapter.notifyDataSetChanged();if(old<shown.size)grid.setSelection(old)
        undoButton.visibility=if(canUndo())View.VISIBLE else View.GONE
        if(snapshot.any {it.privateMode})dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun applyPalette() {
        val p=appearance.palette();panel.background=panel.context.rounded(p.background,24);search.applyTextPalette(p);count.setTextColor(p.text);undoButton.setTextColor(p.accent)
        fun tint(v:View) {if(v is Button)v.setTextColor(p.accent);if(v is ViewGroup && v!==grid)for(i in 0 until v.childCount)tint(v.getChildAt(i))};tint(panel);adapter.notifyDataSetChanged()
    }
}
