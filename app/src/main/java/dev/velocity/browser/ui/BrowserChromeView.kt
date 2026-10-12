package dev.velocity.browser.ui

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import dev.velocity.browser.diagnostics.ScrollDiagnostics
import dev.velocity.browser.diagnostics.ScrollDiagnostics.Event as TraceEvent
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.velocity.browser.R
import org.mozilla.geckoview.GeckoView

/** One GeckoView and one compact toolbar. Session ownership remains with the coordinator. */
class BrowserChromeView(context: Context,private val homeInputs:HomeInputState=HomeInputState()) : FrameLayout(context) {
    private var palette = BrowserPalette.resolve(context,false)
    private val preferences = context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val address = EditText(context).apply {
        id=R.id.address;isSaveEnabled=false;setSingleLine(true);textSize=16f;hint=context.getString(R.string.address)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;imeOptions=EditorInfo.IME_ACTION_GO
        background=null;setPadding(context.dp(4),0,context.dp(2),0)
    }
    // Compatibility state holder: deliberately absent from the visible layout.
    val title = TextView(context)
    val geckoView = GeckoView(context).apply {isSaveEnabled=false;isSaveFromParentEnabled=false}
    val back=BrowserIconButton(context,"back",context.getString(R.string.back))
    val forward=BrowserIconButton(context,"forward",context.getString(R.string.forward))
    val reload=BrowserIconButton(context,"reload",context.getString(R.string.reload))
    private val tabsButton=BrowserIconButton(context,"tabs",context.getString(R.string.tabs))
    private val menuButton=BrowserIconButton(context,"menu","メニュー")
    private val privateTag=TextView(context).apply {text="非公開";textSize=13f;gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE}
    private val info=BrowserIconButton(context,"info","サイト情報・広告ブロック")
    private val toolbar=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
    private val addressGroup=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
    private val start=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(context.dp(24),context.dp(16),context.dp(24),context.dp(16))}
    private val homeSearch=EditText(context).apply {hint="検索またはURL";contentDescription="ホームの検索・URL入力";textSize=16f;setSingleLine(true);isSaveEnabled=false;imeOptions=EditorInfo.IME_ACTION_GO;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;minimumHeight=context.dp(48);filters=arrayOf(android.text.InputFilter.LengthFilter(4096))}
    private val wordmark=TextView(context).apply {text="velocity";textSize=22f;gravity=Gravity.CENTER}
    private val shortcuts=GridLayout(context)
    private val addShortcut=context.actionButton("＋ 追加"){onAddShortcut()}.apply {contentDescription="ショートカットを追加"}
    private val accessibility=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var rawAddress="about:blank"
    private var privateMode=false
    private var pageLoading=false
    private var homeContext:String?=null
    private var starting=true;private var modal=false;private var ime=false
    private val motion=ToolbarMotion()
    private val hidden get()=motion.target==0f
    private val engineContainer=FrameLayout(context)
    internal val toolbarVisibleFraction get()=motion.visible
    internal val toolbarClipping get()=lastClipping
    private var animator:ValueAnimator?=null
    private var dynamicMax=-1
    private var lastClipping=-1
    private val homeSurface=ScrollView(context).apply {isFillViewport=true;addView(start)}
    private val toolbarHeight get()=DesignTokens.toolbarHeight(context)
    var bottomAddress=preferences.getBoolean("address_bottom",true);private set
    var swipeEnabled:Boolean
        get()=preferences.getBoolean("address_swipe",true)
        set(value){preferences.edit().putBoolean("address_swipe",value).apply()}
    var onNavigate:(String)->Unit={};var onTabs:()->Unit={};var onSettings:()->Unit={};var onMenu:()->Unit={onSettings()}
    var onBlock:()->Unit={};var onReading:()->Unit={};var onCreateTab:(Boolean)->Unit={};var onHome:()->Unit={};var onSwitchTab:(Int)->Unit={}
    var onAddShortcut:()->Unit={}
    var onEditingChanged:()->Unit={}
    private val touchExplorationChanged=AccessibilityManager.TouchExplorationStateChangeListener {syncDynamic()}
    private val prefsChanged=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key=="home_shortcuts")refreshShortcuts();if(key=="toolbar_scroll")syncDynamic()}
    init {
        clipChildren=true;engineContainer.addView(geckoView,LayoutParams(-1,-1));addView(engineContainer,LayoutParams(-1,-1));addView(homeSurface,LayoutParams(-1,-1))
        start.addView(wordmark,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=context.dp(24)})
        start.addView(homeSearch,LinearLayout.LayoutParams(-1,-2))
        start.addView(shortcuts,LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(16)})
        addShortcut.setOnClickListener {onAddShortcut()}
        homeSearch.setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_GO){navigate(homeSearch.text.toString(),homeSearch);true}else false}
        toolbar.addView(back,LinearLayout.LayoutParams(context.dp(48),-1))
        addressGroup.addView(info,LinearLayout.LayoutParams(context.dp(48),-1))
        addressGroup.addView(privateTag,LinearLayout.LayoutParams(-2,-1))
        addressGroup.addView(address,LinearLayout.LayoutParams(0,-1,1f))
        addressGroup.addView(reload,LinearLayout.LayoutParams(context.dp(48),-1))
        toolbar.addView(addressGroup,LinearLayout.LayoutParams(0,-1,1f))
        toolbar.addView(tabsButton,LinearLayout.LayoutParams(context.dp(48),-1))
        toolbar.addView(menuButton,LinearLayout.LayoutParams(context.dp(48),-1))
        addView(toolbar,LayoutParams(-1,toolbarHeight,if(bottomAddress)Gravity.BOTTOM else Gravity.TOP))
        tabsButton.setOnClickListener {onTabs()};menuButton.setOnClickListener {onMenu()};info.setOnClickListener {onBlock()}
        address.setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_GO){navigate(address.text.toString(),address);true}else false}
        address.setOnFocusChangeListener {_,focused->
            if(focused){address.setText(if(rawAddress=="about:blank")"" else rawAddress);address.selectAll()}
            else setAddressUrl(rawAddress)
            listOf(back,tabsButton,menuButton).forEach {it.visibility=if(focused)View.GONE else View.VISIBLE}
            info.visibility=if(focused||context.resources.configuration.fontScale>=1.5f)View.GONE else View.VISIBLE
            updateAddressActions();syncDynamic();onEditingChanged()
        }
        homeSearch.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){homeContext?.let {homeInputs.set(it,s?.toString().orEmpty())}}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        homeSearch.setOnFocusChangeListener {_,_->syncDynamic()}
        var x=0f;var y=0f;var editing=false;var longPressed=false
        val detector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onLongPress(e:MotionEvent){longPressed=true;showAddressMenu()}
        })
        address.setOnClickListener {
            if(!address.hasFocus()){
                address.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address,InputMethodManager.SHOW_IMPLICIT)
            }
        }
        address.setOnTouchListener {_,e->
            if(e.actionMasked==MotionEvent.ACTION_DOWN){x=e.x;y=e.y;editing=address.hasFocus();longPressed=false}
            if(editing)false else {
                detector.onTouchEvent(e)
                if(e.actionMasked==MotionEvent.ACTION_UP&&!longPressed){
                    if(swipeEnabled&&kotlin.math.abs(e.x-x)>context.dp(100)&&kotlin.math.abs(e.y-y)<context.dp(40))onSwitchTab(if(e.x>x)-1 else 1)
                    else if(kotlin.math.abs(e.x-x)<context.dp(24)&&kotlin.math.abs(e.y-y)<context.dp(24))address.performClick()
                }
                true
            }
        }
        address.setOnLongClickListener {if(address.hasFocus())false else {showAddressMenu();true}}
        setOnApplyWindowInsetsListener {_,insets->
            trace(TraceEvent.INSETS)
            if(android.os.Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());ime=insets.isVisible(WindowInsets.Type.ime())
                val keyboard=insets.getInsets(WindowInsets.Type.ime());setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom))
            }else{ @Suppress("DEPRECATION") ime=insets.systemWindowInsetBottom>context.dp(100); @Suppress("DEPRECATION") setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            syncDynamic();insets
        }
        info.visibility=if(context.resources.configuration.fontScale>=1.5f)View.GONE else View.VISIBLE
        isFocusableInTouchMode=true;requestFocus();positionHome();refreshShortcuts();applyPalette(palette)
        addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->trace(TraceEvent.LAYOUT);syncDynamic()}
    }
    private fun showAddressMenu(){PopupMenu(context,address).apply {
        menu.add("URLをコピー").setOnMenuItemClickListener {(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("URL",rawAddress));true}
        menu.add("サイト情報").setOnMenuItemClickListener {onBlock();true}
    }.show()}
    private fun navigate(value:String,input:EditText){input.clearFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0);onNavigate(value)}
    private fun positionHome(){homeSurface.setPadding(0,if(bottomAddress)0 else toolbarHeight,0,if(bottomAddress)toolbarHeight else 0)}
    fun setAddressBottom(bottom:Boolean){if(bottomAddress==bottom)return;bottomAddress=bottom;positionHome();preferences.edit().putBoolean("address_bottom",bottom).apply();toolbar.layoutParams=LayoutParams(-1,toolbarHeight,if(bottom)Gravity.BOTTOM else Gravity.TOP);showToolbar(true,false);syncDynamic()}
    fun setAddressUrl(url:String){
        rawAddress=url.ifBlank {"about:blank"}
        if(address.hasFocus())return
        val display=if(url=="about:blank")"" else runCatching {java.net.URI(url).host}.getOrNull()?:url
        if(address.text.toString()!=display)address.setText(display)
        address.contentDescription=(if(privateMode)"非公開 · " else "")+"アドレス・検索"+(if(display.isBlank())"" else " · $display")
    }
    fun setPageLoading(value:Boolean){pageLoading=value;updateAddressActions()}
    private fun updateAddressActions(){
        reload.visibility=if(address.hasFocus()||pageLoading)View.VISIBLE else View.GONE
        privateTag.visibility=if(privateMode&&!address.hasFocus())View.VISIBLE else View.GONE
    }
    fun submitAddress(){navigate(address.text.toString(),address)}
    fun cancelAddressEdit(){address.clearFocus();requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken,0);onEditingChanged()}
    fun setModalBlocked(blocked:Boolean){if(modal==blocked)return;modal=blocked;syncDynamic()}
    private fun locked()=starting||modal||ime||address.hasFocus()||homeSearch.hasFocus()||accessibility.isTouchExplorationEnabled||!preferences.getBoolean("toolbar_scroll",true)
    private fun trace(event:TraceEvent,source:Int=-1,reason:Int=-1,show:Float=Float.NaN){
        if(!ScrollDiagnostics.enabled)return
        val flags=(if(starting)1 else 0) or (if(modal)2 else 0) or (if(ime)4 else 0) or (if(address.hasFocus())8 else 0) or (if(accessibility.isTouchExplorationEnabled)16 else 0)
        ScrollDiagnostics.geometry(motion,toolbarHeight,dynamicMax,flags,lastClipping,toolbar.translationY,engineContainer.translationY)
        ScrollDiagnostics.record(event,source,reason,show=show)
    }
    private fun syncDynamic(){
        if(geckoView.height<=toolbarHeight)return
        // Transient UI locks must not change Gecko's ICB baseline (forms/fixed elements).
        val maximum=if(preferences.getBoolean("toolbar_scroll",true))toolbarHeight else 0
        if(maximum!=dynamicMax){dynamicMax=maximum;geckoView.setDynamicToolbarMaxHeight(maximum);lastClipping=Int.MIN_VALUE;trace(TraceEvent.MAXIMUM)}
        if(geckoView.shouldPinOnScreen()) {trace(TraceEvent.PIN);animator?.cancel();return}
        val wasPinned=motion.state==ToolbarMotion.State.PINNED
        trace(TraceEvent.LOCK)
        motion.lock(locked())
        if(locked()){animator?.cancel();applyOffset(toolbarHeight.toFloat())}
        else {
            if(wasPinned&&motion.unpin(android.os.SystemClock.uptimeMillis()))animateTarget(true)
            applyOffset(motion.visible*toolbarHeight)
        }
    }
    fun onEngineToolbar(show:Boolean,source:ToolbarMotion.Source=ToolbarMotion.Source.CONTENT){
        val now=android.os.SystemClock.uptimeMillis()
        val pinned=geckoView.shouldPinOnScreen()
        if(!pinned){motion.lock(locked());if(motion.unpin(now))animateTarget(true)}
        val accepted=motion.request(show,source,now,pinned)
        trace(TraceEvent.REQUEST,source.ordinal,motion.decision.ordinal,if(show)1f else 0f)
        if(accepted)animateTarget(true)
        else if(locked()&&!pinned){animator?.cancel();applyOffset(toolbarHeight.toFloat())}
    }
    private fun showToolbar(show:Boolean,animate:Boolean){
        if(geckoView.shouldPinOnScreen()) {motion.request(show,ToolbarMotion.Source.UI,android.os.SystemClock.uptimeMillis(),true);animator?.cancel();return}
        if(show&&!animate){animator?.cancel();motion.reset();applyOffset(toolbarHeight.toFloat());return}
        if(motion.request(show,ToolbarMotion.Source.UI,android.os.SystemClock.uptimeMillis()))animateTarget(animate)
    }
    private fun animateTarget(animate:Boolean){
        animator?.cancel();trace(TraceEvent.START)
        val target=motion.target*toolbarHeight
        if(animate&&ValueAnimator.areAnimatorsEnabled()&&!accessibility.isTouchExplorationEnabled){
            animator=ValueAnimator.ofFloat(motion.visible*toolbarHeight,target).apply {
                duration=DesignTokens.ANIMATION_MS
                addListener(object:AnimatorListenerAdapter(){
                    override fun onAnimationCancel(animation:Animator){trace(TraceEvent.CANCEL)}
                    override fun onAnimationEnd(animation:Animator){trace(TraceEvent.END)}
                })
                addUpdateListener {if(geckoView.shouldPinOnScreen()){motion.request(motion.target==1f,ToolbarMotion.Source.UI,android.os.SystemClock.uptimeMillis(),true);cancel()}else {applyOffset(it.animatedValue as Float);trace(TraceEvent.FRAME)}}
                start()
            }
        }else applyOffset(target)
        toolbar.importantForAccessibility=if(motion.target==1f)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private fun applyOffset(visible:Float){
        val geometry=ToolbarGeometry.calculate(toolbarHeight,visible,bottomAddress,dynamicMax>0)
        motion.frame(visible/toolbarHeight)
        toolbar.importantForAccessibility=if(motion.target==1f)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if(toolbar.translationY!=geometry.toolbarTranslation)toolbar.translationY=geometry.toolbarTranslation
        // Move the parent, not GeckoView/SurfaceView; keep session/surface ownership intact.
        if(engineContainer.translationY!=geometry.contentTranslation)engineContainer.translationY=geometry.contentTranslation
        if(geckoView.height>toolbarHeight&&lastClipping!=geometry.clipping){lastClipping=geometry.clipping;geckoView.setVerticalClipping(lastClipping)}
        trace(TraceEvent.APPLY)
    }
    fun showStart(show:Boolean){if(starting==show)return;starting=show;homeSurface.visibility=if(show)View.VISIBLE else View.GONE;syncDynamic()}
    fun updateState(tabCount:Int,privateMode:Boolean,mode:String,lite:Boolean,busy:Boolean,decisions:Int,status:String,selectedId:String?=null,identity:Long?=null,epoch:Long=0){
        ScrollDiagnostics.context(selectedId,identity,epoch,privateMode)
        if(homeContext!=selectedId){showToolbar(true,false);homeContext=selectedId;homeSearch.setText(selectedId?.let(homeInputs::get).orEmpty())}
        if(this.privateMode!=privateMode){this.privateMode=privateMode;applyPalette(palette);setAddressUrl(rawAddress)}
        updateAddressActions()
        tabsButton.count=tabCount
        val description="タブ $tabCount 件"+(if(privateMode)" · 非公開" else "")
        if(tabsButton.contentDescription!=description)tabsButton.contentDescription=description
        val hint=if(privateMode)"非公開 · URLまたは検索語" else context.getString(R.string.address)
        if(address.hint.toString()!=hint)address.hint=hint
        val state="サイト情報・広告ブロック · "+when {
            busy->"準備中"
            status.contains("FAILED")||status.contains("INVALID")||status=="DISCONNECTED"->"状態を確認してください"
            lite&&!status.startsWith("READY_CONFIG_ACK")->"Lite · 接続確認中"
            lite->"Lite · 遮断判定 $decisions"
            mode=="ADGUARD"&&privateMode->"非公開 · AdGuard OFF"
            else->mode
        }
        if(info.contentDescription!=state)info.contentDescription=state
    }
    fun refreshShortcuts(){
        shortcuts.removeAllViews()
        val columns=if(context.resources.configuration.fontScale>=1.5f)2 else if(context.resources.configuration.screenWidthDp>=600)4 else 3
        shortcuts.columnCount=columns
        fun add(view:View,index:Int){shortcuts.addView(view,GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)).apply {
            width=0;height=-2;setMargins(context.dp(4),context.dp(4),context.dp(4),context.dp(4))
        })}
        val entries=runCatching {org.json.JSONArray(preferences.getString("home_shortcuts","[]"))}.getOrElse {org.json.JSONArray()}
        var count=0
        for(i in 0 until minOf(entries.length(),12)){
            val item=entries.optJSONObject(i)?:continue;val url=item.optString("url")
            if(dev.velocity.browser.security.UrlPolicy.httpUrl(url)==null)continue
            val name=item.optString("title",url)
            add(context.actionButton((name.firstOrNull()?.uppercaseChar()?:'↗').toString()+"\n"+name){onNavigate(url)}.apply {
                contentDescription="ショートカット · $name";textSize=14f;minHeight=context.dp(88);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END
                setOnLongClickListener {onAddShortcut();true}
            },count++)
        }
        add(addShortcut,count);applyPalette(palette)
    }
    fun applyPalette(next:BrowserPalette){
        palette=next;setBackgroundColor(next.background);start.setBackgroundColor(next.background);toolbar.setBackgroundColor(next.surface)
        addressGroup.background=context.rounded(next.card,DesignTokens.ADDRESS_RADIUS,if(privateMode)next.privateAccent else null)
        address.applyTextPalette(next);address.background=null
        homeSearch.applyTextPalette(next);homeSearch.backgroundTintList=null;homeSearch.setPadding(context.dp(16),context.dp(12),context.dp(16),context.dp(12));homeSearch.background=context.rounded(next.surface,DesignTokens.ADDRESS_RADIUS,next.border)
        wordmark.setTextColor(next.muted);privateTag.setTextColor(next.privateAccent)
        listOf(back,forward,reload,tabsButton,menuButton,info).forEach {it.palette=next}
        addShortcut.actionPalette(next)
        for(i in 0 until shortcuts.childCount)(shortcuts.getChildAt(i) as TextView).actionPalette(next)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();preferences.registerOnSharedPreferenceChangeListener(prefsChanged);accessibility.addTouchExplorationStateChangeListener(touchExplorationChanged)}
    override fun onDetachedFromWindow(){preferences.unregisterOnSharedPreferenceChangeListener(prefsChanged);accessibility.removeTouchExplorationStateChangeListener(touchExplorationChanged);animator?.cancel();super.onDetachedFromWindow()}
}
