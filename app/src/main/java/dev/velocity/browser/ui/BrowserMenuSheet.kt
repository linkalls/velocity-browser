package dev.velocity.browser.ui

import android.content.Context
import android.view.*
import android.widget.*

/** Quick actions have their own compact sheet instead of a reused settings screen. */
class BrowserMenuSheet(context: Context, private val appearance: ThemeController,
    quick: List<Action>, groups: List<Pair<String,List<Action>>>, onDismiss: () -> Unit) {
    data class Action(val label: String, val icon: String = "", val enabled: Boolean = true, val run: () -> Unit)
    private val panel=LinearLayout(context).apply {setPadding(context.dp(16),context.dp(4),context.dp(16),context.dp(12))}
    private val sheet=VelocitySheet(context,appearance,panel,onDismiss)
    private val labels=mutableListOf<TextView>()
    private val buttons=mutableListOf<TextView>()
    private val icons=mutableListOf<BrowserIconButton>()
    private val themeChanged:()->Unit={applyPalette()}
    init {
        val settings=groups.flatMap {it.second}.firstOrNull {it.label=="設定"}
        panel.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(context.sectionHeader("メニュー").also {labels+=it},LinearLayout.LayoutParams(0,-2,1f))
            settings?.let {a->addView(context.actionButton(a.label){sheet.dismiss();a.run()}.also {buttons+=it})}
            addView(context.actionButton("閉じる"){sheet.dismiss()}.also {buttons+=it})
        })
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        content.addView(LinearLayout(context).apply {
            quick.forEach {a->
                addView(LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                    val icon=BrowserIconButton(context,a.icon,a.label).apply {isEnabled=a.enabled;setOnClickListener {sheet.dismiss();a.run()};icons+=this}
                    addView(icon,LinearLayout.LayoutParams(-1,context.dp(48)))
                    addView(TextView(context).apply {text=a.label;textSize=13f;gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;labels+=this},LinearLayout.LayoutParams(-1,-2))
                },LinearLayout.LayoutParams(0,-2,1f))
            }
        })
        groups.forEach {(title,actions)->
            val rows=actions.filter {it!==settings}
            if(rows.isNotEmpty())content.addView(context.sectionHeader(title).also {labels+=it})
            rows.forEach {a->content.addView(context.actionButton(a.label){sheet.dismiss();a.run()}.apply {
                gravity=Gravity.START or Gravity.CENTER_VERTICAL;isEnabled=a.enabled;alpha=if(a.enabled)1f else .45f;buttons+=this
            },LinearLayout.LayoutParams(-1,-2))}
        }
        // Bounded content height; landscape/large fonts scroll within the sheet.
        val scroll=object:ScrollView(context){override fun onMeasure(w:Int,h:Int){super.onMeasure(w,MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels*.65).toInt(),MeasureSpec.AT_MOST))}}.apply {addView(content)}
        panel.addView(scroll)
        sheet.dialog.setOnDismissListener {appearance.remove(themeChanged);onDismiss()}
    }
    fun show(){sheet.show();appearance.observe(themeChanged);applyPalette()}
    fun dismiss()=sheet.dismiss()
    val isShowing get()=sheet.dialog.isShowing
    private fun applyPalette(){val p=appearance.palette();sheet.applyPalette();labels.forEach {it.setTextColor(p.muted)};buttons.forEach {it.actionPalette(p)};icons.forEach {it.palette=p}}
}
