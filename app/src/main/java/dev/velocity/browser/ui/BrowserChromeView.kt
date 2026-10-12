package dev.velocity.browser.ui

import android.animation.ValueAnimator
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
    private val wordmark=TextView(context).apply {text="Velocity";textSize=28f;gravity=Gravity.CENTER}
    private val shortcuts=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
    private val addShortcut=Button(context).apply {text="ショートカットを追加";isAllCaps=false;minHeight=context.dp(48)}
    private val accessibility=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var homeContext:String?=null
    private var starting=true;private var modal=false;private var ime=false;private var hidden=false
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
        clipChildren=true;addView(geckoView,LayoutParams(-1,-1));addView(homeSurface,LayoutParams(-1,-1))
        start.addView(wordmark,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=context.dp(24)})
        start.addView(homeSearch,LinearLayout.LayoutParams(-1,-2))
        start.addView(shortcuts,LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(16)})
        start.addView(addShortcut,LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(12)})
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
            listOf(back,tabsButton,menuButton).forEach {it.visibility=if(focused)View.GONE else View.VISIBLE}
            info.visibility=if(focused||context.resources.configuration.fontScale>=1.5f)View.GONE else View.VISIBLE
            syncDynamic();onEditingChanged()
        }
        homeSearch.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){homeContext?.let {homeInputs.set(it,s?.toString().orEmpty())}}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        homeSearch.setOnFocusChangeListener {_,_->syncDynamic()}
        var x=0f;var y=0f;var editing=false
        address.setOnTouchListener {_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{x=e.x;y=e.y;editing=address.hasFocus();false}
            MotionEvent.ACTION_UP->if(swipeEnabled&&!editing&&kotlin.math.abs(e.x-x)>context.dp(100)&&kotlin.math.abs(e.y-y)<context.dp(40)){onSwitchTab(if(e.x>x)-1 else 1);true}else false
            else->false
        }}
        address.setOnLongClickListener {if(address.hasFocus())false else {PopupMenu(context,address).apply {
            menu.add("URLをコピー").setOnMenuItemClickListener {(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("URL",address.text.toString()));true}
            menu.add("サイト情報").setOnMenuItemClickListener {onBlock();true}
        }.show();true}}
        setOnApplyWindowInsetsListener {_,insets->
            if(android.os.Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());ime=insets.isVisible(WindowInsets.Type.ime())
                val keyboard=insets.getInsets(WindowInsets.Type.ime());setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom))
            }else{ @Suppress("DEPRECATION") ime=insets.systemWindowInsetBottom>context.dp(100); @Suppress("DEPRECATION") setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            syncDynamic();insets
        }
        info.visibility=if(context.resources.configuration.fontScale>=1.5f)View.GONE else View.VISIBLE
        isFocusableInTouchMode=true;requestFocus();positionHome();refreshShortcuts();applyPalette(palette)
        addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->applyOffset(if(hidden)0f else toolbarHeight.toFloat());syncDynamic()}
    }
    private fun navigate(value:String,input:EditText){input.clearFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0);onNavigate(value)}
    private fun positionHome(){homeSurface.setPadding(0,if(bottomAddress)0 else toolbarHeight,0,if(bottomAddress)toolbarHeight else 0)}
    fun setAddressBottom(bottom:Boolean){if(bottomAddress==bottom)return;bottomAddress=bottom;positionHome();preferences.edit().putBoolean("address_bottom",bottom).apply();toolbar.layoutParams=LayoutParams(-1,toolbarHeight,if(bottom)Gravity.BOTTOM else Gravity.TOP);showToolbar(true,false);syncDynamic()}
    fun submitAddress(){navigate(address.text.toString(),address)}
    fun cancelAddressEdit(){address.clearFocus();requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken,0);onEditingChanged()}
    fun setModalBlocked(blocked:Boolean){if(modal==blocked)return;modal=blocked;syncDynamic()}
    private fun locked()=starting||modal||ime||address.hasFocus()||homeSearch.hasFocus()||accessibility.isTouchExplorationEnabled||!preferences.getBoolean("toolbar_scroll",true)
    private fun syncDynamic(){if(geckoView.height<=toolbarHeight)return;val maximum=if(locked())0 else toolbarHeight;if(maximum!=dynamicMax){dynamicMax=maximum;geckoView.setDynamicToolbarMaxHeight(maximum);lastClipping=-1};if(locked()){showToolbar(true,false);applyOffset(toolbarHeight.toFloat())}else applyOffset(if(hidden)0f else toolbarHeight.toFloat())}
    fun onEngineToolbar(show:Boolean){if(!show&&(locked()||geckoView.shouldPinOnScreen()))return;showToolbar(show,true)}
    private fun showToolbar(show:Boolean,animate:Boolean){
        if(hidden==!show){if(!animate){animator?.cancel();applyOffset(if(show)toolbarHeight.toFloat() else 0f)};return;}
        hidden=!show;animator?.cancel()
        val target=if(show)toolbarHeight.toFloat() else 0f
        val current=if(bottomAddress)toolbarHeight-toolbar.translationY else toolbarHeight+toolbar.translationY
        if(animate&&ValueAnimator.areAnimatorsEnabled()){
            animator=ValueAnimator.ofFloat(current,target).apply {duration=DesignTokens.ANIMATION_MS;addUpdateListener {applyOffset(it.animatedValue as Float)};start()}
        }else applyOffset(target)
        toolbar.importantForAccessibility=if(show)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private fun applyOffset(visible:Float){
        toolbar.translationY=if(bottomAddress)toolbarHeight-visible else visible-toolbarHeight
        geckoView.translationY=if(bottomAddress)0f else visible
        if(geckoView.height>toolbarHeight && lastClipping!=visible.toInt()){lastClipping=visible.toInt();geckoView.setVerticalClipping(lastClipping)}

    }
    fun showStart(show:Boolean){if(starting==show)return;starting=show;homeSurface.visibility=if(show)View.VISIBLE else View.GONE;syncDynamic()}
    fun updateState(tabCount:Int,privateMode:Boolean,mode:String,lite:Boolean,busy:Boolean,decisions:Int,status:String,selectedId:String?=null){
        if(homeContext!=selectedId){showToolbar(true,false);homeContext=selectedId;homeSearch.setText(selectedId?.let(homeInputs::get).orEmpty())}
        privateTag.visibility=if(privateMode)View.VISIBLE else View.GONE
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
    fun refreshShortcuts(){shortcuts.removeAllViews();val entries=runCatching {org.json.JSONArray(preferences.getString("home_shortcuts","[]"))}.getOrElse {org.json.JSONArray()}
        for(i in 0 until minOf(entries.length(),12)){val item=entries.optJSONObject(i)?:continue;val url=item.optString("url");if(dev.velocity.browser.security.UrlPolicy.httpUrl(url)==null)continue
            shortcuts.addView(Button(context).apply {text=item.optString("title",url);isAllCaps=false;stateListAnimator=null;elevation=0f;background=null;minHeight=context.dp(48);setOnClickListener {onNavigate(url)};setOnLongClickListener {onAddShortcut();true}},LinearLayout.LayoutParams(-1,-2))
        };applyPalette(palette)
    }
    fun applyPalette(next:BrowserPalette){palette=next;setBackgroundColor(next.background);start.setBackgroundColor(next.background);toolbar.setBackgroundColor(next.surface);addressGroup.background=context.rounded(next.background,14);address.applyTextPalette(next);address.background=null;homeSearch.applyTextPalette(next);homeSearch.background=context.rounded(next.surface,14);wordmark.setTextColor(next.text);privateTag.setTextColor(next.muted)
        listOf(back,forward,reload,tabsButton,menuButton,info).forEach {it.palette=next}
        addShortcut.setTextColor(next.muted);addShortcut.background=null
        for(i in 0 until shortcuts.childCount)(shortcuts.getChildAt(i) as TextView).setTextColor(next.text)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();preferences.registerOnSharedPreferenceChangeListener(prefsChanged);accessibility.addTouchExplorationStateChangeListener(touchExplorationChanged)}
    override fun onDetachedFromWindow(){preferences.unregisterOnSharedPreferenceChangeListener(prefsChanged);accessibility.removeTouchExplorationStateChangeListener(touchExplorationChanged);animator?.cancel();super.onDetachedFromWindow()}
}
