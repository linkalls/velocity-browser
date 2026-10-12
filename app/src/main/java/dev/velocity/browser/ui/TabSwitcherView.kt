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
    private val canUndo: () -> Boolean = { false }, private val pin: (String) -> Unit = {}, private val organize: () -> Unit = {}) {
    private val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)

    private val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(12),context.dp(12),context.dp(12),context.dp(12)) }
    private val sheet=VelocitySheet(context,appearance,panel)
    private val dialog get()=sheet.dialog
    private val count=TextView(context).apply { textSize=22f }
    private val search=context.searchField("タブを検索")
    private val grid=GridView(context).apply { verticalSpacing=context.dp(8);horizontalSpacing=context.dp(8);stretchMode=GridView.STRETCH_COLUMN_WIDTH }
    private val empty=context.emptyState("タブがありません\n＋ から新しいタブを追加できます")
    private lateinit var normalButton:Button
    private lateinit var privateButton:Button
    private val undoButton=Button(context).apply { text="閉じるを取り消す（ページを再読み込み）";isAllCaps=false;minHeight=context.dp(48);setOnClickListener { undo();filter() } }
    private data class DisplayKey(val id:String,val title:String,val url:String,val privateMode:Boolean,val pinned:Boolean,val identity:Long?,val protected:Boolean)
    private var displayKeys=emptyList<DisplayKey>()
    private var snapshot=emptyList<TabRecord>();private var shown=emptyList<TabRecord>();private var selected:String?=null
    private var privateMode=false
    private var columns=if(preferences.getBoolean("tabs_list",true)) 1 else 2
    private val stableIds=mutableMapOf<String,Long>();private var nextStableId=0L
    private class RowViews(val root:LinearLayout,val title:TextView,val domain:TextView,val state:TextView,val menu:BrowserIconButton,val close:BrowserIconButton,val favicon:TextView,val grid:Boolean)
    private val adapter=object:BaseAdapter() {
        override fun getCount()=shown.size
        override fun getItem(position:Int)=shown[position]
        override fun hasStableIds()=true
        override fun getItemId(position:Int)=stableIds.getOrPut(shown[position].id){++nextStableId}
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val gridMode=columns==2
            val cached=convertView?.tag as? RowViews
            val row=if(cached!=null&&cached.grid==gridMode)cached else {
                val root=LinearLayout(context).apply {orientation=if(gridMode)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=context.dp(72);setPadding(context.dp(8),context.dp(8),context.dp(4),context.dp(8))}
                val label=TextView(context).apply {textSize=16f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
                val domain=TextView(context).apply {textSize=13f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,context.dp(4),0,0)}
                val state=TextView(context).apply {textSize=13f}
                val favicon=TextView(context).apply {textSize=16f;gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
                root.addView(favicon,LinearLayout.LayoutParams(context.dp(32),context.dp(32)).apply {marginEnd=context.dp(12)})
                val text=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;addView(label);addView(domain)}
                if(gridMode)root.addView(text,LinearLayout.LayoutParams(-1,-2)) else root.addView(text,LinearLayout.LayoutParams(0,-2,1f))
                val menu=BrowserIconButton(context,"menu","タブの操作")
                val closeButton=BrowserIconButton(context,"close","閉じる")
                val actions=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
                actions.addView(state,LinearLayout.LayoutParams(if(gridMode)0 else context.dp(16),-2,if(gridMode)1f else 0f))
                actions.addView(menu,LinearLayout.LayoutParams(context.dp(48),context.dp(48)));actions.addView(closeButton,LinearLayout.LayoutParams(context.dp(48),context.dp(48)))
                root.addView(actions,LinearLayout.LayoutParams(if(gridMode)-1 else -2,-2))
                RowViews(root,label,domain,state,menu,closeButton,favicon,gridMode).also {root.tag=it}
            }
            val tab=shown[position];val p=appearance.palette()
            row.root.background=context.rounded(if(tab.id==selected)p.pressed else p.surface,DesignTokens.CARD_RADIUS)
            row.favicon.text=tab.displayLocation().firstOrNull()?.uppercaseChar()?.toString()?:"↗"
            row.favicon.setTextColor(if(tab.privateMode)p.privateAccent else p.muted);row.favicon.background=context.rounded(p.card,8)
            row.title.text=tab.displayTitle();row.title.setTextColor(p.text)
            row.domain.text=tab.displayLocation();row.domain.setTextColor(p.muted)
            val state=when {tab.pinned->"固定";tab.protections.isNotEmpty()->"保護中";!tab.live->"休眠中";else->""}
            row.state.text=if(state.isEmpty())"" else "•";row.state.contentDescription=state;row.state.setTextColor(p.muted)
            row.menu.palette=p;row.close.palette=p
            fun menu(){PopupMenu(context,row.menu).apply {
                menu.add(if(tab.pinned)"固定解除" else "固定").setOnMenuItemClickListener {pin(tab.id);true}
                menu.add("閉じる").setOnMenuItemClickListener {close(tab.id);true}
                menu.add("タブを整理").setOnMenuItemClickListener {organize();true}
                menu.add("状態: "+state.ifEmpty {"通常"}).isEnabled=false
            }.show()}
            row.menu.contentDescription="『${tab.displayTitle()}』の操作";row.menu.setOnClickListener {menu()}
            row.close.contentDescription="『${tab.displayTitle()}』を閉じる";row.close.setOnClickListener {close(tab.id)}
            row.root.isFocusable=true;row.root.contentDescription="『${tab.displayTitle()}』を選択"+(if(state.isEmpty())"" else " · $state")+(if(tab.privateMode)" · 非公開" else "")
            row.root.setOnClickListener {if(select(tab.id))dismiss()};row.root.setOnLongClickListener {menu();true}
            var x=0f;var y=0f
            row.root.setOnTouchListener {_,e->when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{x=e.x;y=e.y;false}
                MotionEvent.ACTION_UP->if(kotlin.math.abs(e.x-x)>context.dp(100)&&kotlin.math.abs(e.y-y)<context.dp(40)){close(tab.id);true}else false
                else->false
            }}
            return row.root
        }
    }
    val isShowing get()=dialog.isShowing
    init {
        fun button(label:String,action:()->Unit)=context.actionButton(label,action)
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        header.addView(count,LinearLayout.LayoutParams(0,-2,1f));header.addView(button("＋") {if(create(privateMode))dismiss()}.apply {contentDescription="新しいタブ"})
        header.addView(button("完了",::dismiss));panel.addView(header);panel.addView(search)
        panel.addView(LinearLayout(context).apply {
            addView(button("通常") {privateMode=false;filter()}.also {normalButton=it},LinearLayout.LayoutParams(0,-2,1f))
            addView(button("非公開タブ") {privateMode=true;filter()}.also {privateButton=it},LinearLayout.LayoutParams(0,-2,1f))
            addView(button("表示切替") {columns=if(columns==2)1 else 2;preferences.edit().putBoolean("tabs_list",columns==1).apply();grid.numColumns=columns;adapter.notifyDataSetChanged()},LinearLayout.LayoutParams(0,-2,1f))
        })
        grid.numColumns=columns;grid.adapter=adapter;panel.addView(FrameLayout(context).apply {addView(grid,FrameLayout.LayoutParams(-1,-1));addView(empty,FrameLayout.LayoutParams(-1,-1));grid.emptyView=empty},LinearLayout.LayoutParams(-1,0,1f));panel.addView(undoButton);panel.addView(button("一括整理",organize))
        search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()};override fun afterTextChanged(s:Editable?){} })
    }
    fun show() {sheet.show(true);applyPalette();panel.isFocusableInTouchMode=true;panel.requestFocus()}
    fun dismiss()=dialog.dismiss()
    fun update(tabs:List<TabRecord>,selectedId:String?,waiting:String?) {val keys=tabs.map {DisplayKey(it.id,it.title,it.url,it.privateMode,it.pinned,it.identity,it.protections.isNotEmpty())};if(keys==displayKeys&&selected==selectedId)return;displayKeys=keys;snapshot=tabs;selected=selectedId;if(!isShowing)privateMode=tabs.find {it.id==selectedId}?.privateMode==true;filter()}
    private fun filter() {
        val query=search.text.toString().trim();val old=grid.firstVisiblePosition;val first=shown.getOrNull(old)?.id
        shown=snapshot.filter {it.privateMode==privateMode && (query.isEmpty() || it.displayTitle().contains(query,true) || it.displayLocation().contains(query,true))}
        count.text=(if(privateMode)"非公開" else "タブ")+" ${shown.size}枚";adapter.notifyDataSetChanged();val retained=shown.indexOfFirst {it.id==first};if(shown.isNotEmpty())grid.setSelection(if(retained>=0)retained else old.coerceAtMost(shown.lastIndex))
        undoButton.visibility=if(!privateMode&&canUndo())View.VISIBLE else View.GONE
        normalButton.text="通常 ${snapshot.count {!it.privateMode}}";privateButton.text="非公開タブ ${snapshot.count {it.privateMode}}"
        normalButton.actionPalette(appearance.palette(),!privateMode);privateButton.actionPalette(appearance.palette(),privateMode)
        if(snapshot.any {it.privateMode})dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun applyPalette() {
        val p=appearance.palette();sheet.applyPalette();search.applyTextPalette(p);search.background=panel.context.rounded(p.card,DesignTokens.CONTROL_RADIUS);count.setTextColor(if(privateMode)p.privateAccent else p.text);empty.setTextColor(p.muted)
        fun tint(v:View) {if(v is Button)v.actionPalette(p);if(v is ViewGroup&&v!==grid)for(i in 0 until v.childCount)tint(v.getChildAt(i))}
        tint(panel);normalButton.actionPalette(p,!privateMode);privateButton.actionPalette(p,privateMode);adapter.notifyDataSetChanged()
    }
}
