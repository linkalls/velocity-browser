package dev.velocity.browser.diagnostics

import android.os.SystemClock
import dev.velocity.browser.BuildConfig
import dev.velocity.browser.ui.ScrollTraceBuffer
import dev.velocity.browser.ui.ToolbarMotion

/** Opt-in Debug only. No automatic IO/network/logcat; private/null selection clears scope. */
object ScrollDiagnostics {
    enum class Event { CONTEXT, SAMPLE, REQUEST, START, CANCEL, END, FRAME, LAYOUT, INSETS, PIN, LOCK, MAXIMUM, APPLY, RESET }
    var enabled=false; private set
    private var buffer:ScrollTraceBuffer?=null
    private var selected:String?=null
    private var tab=0;private var ordinal=0;private var identity=0L;private var epoch=0L
    private var height=0;private var maximum=0;private var flags=0
    private var visible=1f;private var target=1f;private var state=0
    private var clipping=0f;private var toolbar=0f;private var content=0f
    private var lastFrame=0L
    fun enable(value:Boolean){enabled=BuildConfig.DEBUG&&value;buffer=if(enabled)ScrollTraceBuffer() else null;selected=null;tab=0;ordinal=0;lastFrame=0}
    fun context(id:String?,identity:Long?,epoch:Long,privateMode:Boolean){
        if(!BuildConfig.DEBUG||!enabled)return
        if(privateMode||id==null){buffer?.clear();selected=null;tab=0;lastFrame=0;return}
        if(selected!=id||this.identity!=identity||this.epoch!=epoch){
            selected=id;this.identity=identity?:0;this.epoch=epoch;tab=++ordinal;lastFrame=0;record(Event.CONTEXT)
        }
    }
    fun geometry(motion:ToolbarMotion,height:Int,maximum:Int,flags:Int,clipping:Int,toolbar:Float,content:Float){
        if(!BuildConfig.DEBUG||!enabled||tab==0)return
        this.height=height;this.maximum=maximum;this.flags=flags;this.visible=motion.visible;this.target=motion.target;this.state=motion.state.ordinal
        this.clipping=if(clipping==Int.MIN_VALUE)Float.NaN else clipping.toFloat();this.toolbar=toolbar;this.content=content
    }
    fun record(event:Event,source:Int=-1,reason:Int=-1,y:Float=Float.NaN,zoom:Float=Float.NaN,delta:Float=Float.NaN,intent:Float=Float.NaN,show:Float=Float.NaN){
        if(!BuildConfig.DEBUG||!enabled||tab==0)return
        val now=SystemClock.uptimeMillis()
        val gap=if(event==Event.FRAME&&lastFrame!=0L)(now-lastFrame).toFloat() else Float.NaN
        if(event==Event.START)lastFrame=now
        if(event==Event.FRAME)lastFrame=now
        if(event==Event.END||event==Event.CANCEL||event==Event.PIN)lastFrame=0
        buffer?.add(now,event.ordinal,tab,identity,epoch,source,reason,state,height,maximum,flags,y,zoom,delta,intent,visible,target,clipping,toolbar,content,gap,show)
    }
    fun snapshot():String=if(BuildConfig.DEBUG&&enabled)buffer?.csv().orEmpty() else ""
    val size get()=if(BuildConfig.DEBUG&&enabled)buffer?.size?:0 else 0
}
