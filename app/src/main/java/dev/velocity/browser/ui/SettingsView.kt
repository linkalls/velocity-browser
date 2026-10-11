package dev.velocity.browser.ui

import android.app.Dialog
import android.content.Context
import android.view.*
import android.widget.*

/** Full-height native settings with categories, visible values and ordinary Back dismissal. */
class SettingsView(context:Context,private val appearance:ThemeController,title:String,rows:List<Row>) {
    data class Row(val category:String,val label:String,val detail:String="",val action:()->Unit)
    private val dialog=Dialog(context).apply {requestWindowFeature(Window.FEATURE_NO_TITLE)}
    private val panel=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(context.dp(16),context.dp(12),context.dp(16),context.dp(12))}
    private val refresh:()->Unit={applyPalette()}
    init {
        panel.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {text=title;textSize=26f},LinearLayout.LayoutParams(0,-2,1f))
            addView(Button(context).apply {text="完了";minHeight=context.dp(48);setOnClickListener {dialog.dismiss()} })
        })
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        var previous=""
        rows.forEach {row->
            if(row.category!=previous){previous=row.category;content.addView(TextView(context).apply {text=row.category;textSize=14f;setPadding(0,context.dp(20),0,context.dp(6))})}
            content.addView(Button(context).apply {
                text=row.label+(if(row.detail.isBlank())"" else "\n${row.detail}");isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL;minHeight=context.dp(56);setPadding(context.dp(12),context.dp(8),context.dp(12),context.dp(8))
                setOnClickListener {row.action()}
            },LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=context.dp(4)})
        }
        panel.addView(ScrollView(context).apply {addView(content)},LinearLayout.LayoutParams(-1,0,1f));dialog.setContentView(panel)
        dialog.setOnDismissListener {appearance.remove(refresh)}
    }
    fun show() {dialog.show();dialog.window?.apply {setLayout(-1,-1);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)};appearance.track(dialog);appearance.observe(refresh);applyPalette()}
    private fun applyPalette(){val p=appearance.palette();panel.setBackgroundColor(p.background);fun visit(v:View){if(v is Button){v.setTextColor(p.text);v.background=v.context.rounded(p.card,12,p.border)}else if(v is TextView)v.setTextColor(p.muted);if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))};visit(panel)}
}
