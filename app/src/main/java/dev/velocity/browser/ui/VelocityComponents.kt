package dev.velocity.browser.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*

/** Shared native controls. No thumbnails, blur or extra UI dependencies. */
fun Context.actionButton(label: String, action: () -> Unit) = Button(this).apply {
    text=label;contentDescription=label;isAllCaps=false;stateListAnimator=null;elevation=0f
    minHeight=dp(DesignTokens.TOUCH_DP);minimumWidth=dp(48);textSize=DesignTokens.TEXT_SP
    setPadding(dp(12),dp(8),dp(12),dp(8));setOnClickListener {action()}
}
fun TextView.actionPalette(p: BrowserPalette, selected: Boolean=false) {
    setTextColor(if(selected)p.accent else p.text)
    background=RippleDrawable(ColorStateList.valueOf(p.pressed),context.rounded(if(selected)p.pressed else p.surface,DesignTokens.CONTROL_RADIUS),null)
}
fun Context.sectionHeader(label: String) = TextView(this).apply {
    text=label;textSize=DesignTokens.SECONDARY_SP;setPadding(dp(4),dp(20),0,dp(8))
    typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
    if(android.os.Build.VERSION.SDK_INT>=28)isAccessibilityHeading=true
}
fun Context.searchField(label: String)=EditText(this).apply {
    hint=label;contentDescription=label;textSize=DesignTokens.TEXT_SP;setSingleLine(true)
    isSaveEnabled=false;minimumHeight=dp(48);setPadding(dp(16),dp(12),dp(16),dp(12))
}
fun Context.emptyState(label: String)=TextView(this).apply {
    text=label;textSize=DesignTokens.TEXT_SP;gravity=Gravity.CENTER
    setPadding(dp(16),dp(24),dp(16),dp(24));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/** Window/insets and dismissal shared by action sheets and tab manager. */
class VelocitySheet(private val context: Context, private val appearance: ThemeController,
    val panel: LinearLayout, private val onDismiss: () -> Unit = {}) {
    val dialog=Dialog(context).apply {requestWindowFeature(Window.FEATURE_NO_TITLE)}
    private var downY=0f
    init {
        panel.orientation=LinearLayout.VERTICAL;panel.isFocusableInTouchMode=true
        val handle=FrameLayout(context).apply {
            minimumHeight=context.dp(24);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(View(context).apply {background=context.rounded(appearance.palette().border,4)},FrameLayout.LayoutParams(context.dp(32),context.dp(4),Gravity.CENTER))
            setOnTouchListener {_,e->when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downY=e.rawY;true}
                MotionEvent.ACTION_UP->{if(e.rawY-downY>context.dp(64))dialog.dismiss();true}
                else->true
            }}
        }
        panel.addView(handle,0);dialog.setContentView(panel)
        dialog.setOnDismissListener {onDismiss()}
        panel.setOnApplyWindowInsetsListener {_,insets->
            if(android.os.Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime=insets.getInsets(WindowInsets.Type.ime())
                panel.setPadding(context.dp(16)+bars.left,context.dp(4),context.dp(16)+bars.right,context.dp(12)+maxOf(bars.bottom,ime.bottom))
            };insets
        }
    }
    fun show(full: Boolean=false) {
        dialog.show();appearance.track(dialog)
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM);setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1,if(full)(context.resources.displayMetrics.heightPixels*.9).toInt() else -2)
            if(android.os.Build.VERSION.SDK_INT>=30)setDecorFitsSystemWindows(false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        panel.requestApplyInsets();panel.requestFocus();applyPalette()
    }
    fun applyPalette(){dialog.window?.setBackgroundDrawableResource(android.R.color.transparent);panel.background=context.rounded(appearance.palette().surface,DesignTokens.SHEET_RADIUS)}
    fun dismiss()=dialog.dismiss()
}
