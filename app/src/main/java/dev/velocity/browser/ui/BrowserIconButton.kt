package dev.velocity.browser.ui

import android.content.Context
import android.graphics.*
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** Uniform native line icons; text remains available to accessibility services. */
class BrowserIconButton(context: Context, private val icon: String, label: String) : Button(context) {
    var palette = BrowserPalette.resolve(context, false)
        set(value) { field = value; background = RippleDrawable(ColorStateList.valueOf(value.pressed), null, context.rounded(-1, 8)); invalidate() }
    var count = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        stateListAnimator=null;elevation=0f
        text = label; contentDescription = label; tooltipText = label; isAllCaps = false
        minWidth = 0; minimumWidth = context.dp(48); minHeight = context.dp(48); setPadding(0,0,0,0)
        palette = palette
    }
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(resources.displayMetrics.density, resources.displayMetrics.density)
        pen.color = palette.text; pen.alpha = if (isEnabled) 255 else 80; pen.strokeWidth = 1.8f
        pen.strokeCap = Paint.Cap.ROUND; pen.strokeJoin = Paint.Join.ROUND; pen.style = Paint.Style.STROKE
        fun path(vararg p: Float) { val shape=Path();shape.moveTo(p[0],p[1]);for(i in 2 until p.size step 2)shape.lineTo(p[i],p[i+1]);canvas.drawPath(shape,pen) }
        when(icon) {
            "back" -> path(4f,-8f,-4f,0f,4f,8f)
            "forward" -> path(-4f,-8f,4f,0f,-4f,8f)
            "reload" -> if(text.toString()=="開く")path(-7f,0f,7f,0f,1f,-6f,7f,0f,1f,6f) else if(text.toString()=="中断")canvas.drawRoundRect(-6f,-6f,6f,6f,1f,1f,pen) else {canvas.drawArc(-8f,-8f,8f,8f,40f,285f,false,pen);path(8f,-8f,8f,-2f,2f,-2f)}
            "tabs" -> {canvas.drawRoundRect(-9f,-9f,9f,9f,2f,2f,pen);pen.style=Paint.Style.FILL;pen.textSize=10f;pen.textAlign=Paint.Align.CENTER;canvas.drawText(if(count>99)"99+" else count.toString(),0f,3.5f,pen)}
            "menu" -> {pen.style=Paint.Style.FILL;for(y in listOf(-6f,0f,6f))canvas.drawCircle(0f,y,1.6f,pen)}
            "share" -> {path(-6f,0f,5f,-6f);path(-6f,0f,5f,6f);for(p in listOf(-6f to 0f,5f to -6f,5f to 6f))canvas.drawCircle(p.first,p.second,2.5f,pen)}
            "info" -> {canvas.drawCircle(0f,0f,8f,pen);canvas.drawLine(0f,-1f,0f,5f,pen);pen.style=Paint.Style.FILL;canvas.drawCircle(0f,-4f,1f,pen)}
            "add" -> {canvas.drawLine(-8f,0f,8f,0f,pen);canvas.drawLine(0f,-8f,0f,8f,pen)}
            "close" -> {canvas.drawLine(-6f,-6f,6f,6f,pen);canvas.drawLine(6f,-6f,-6f,6f,pen)}
        }
        canvas.restore()
    }
}
