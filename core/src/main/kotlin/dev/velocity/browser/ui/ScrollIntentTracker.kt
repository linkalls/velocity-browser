package dev.velocity.browser.ui

import kotlin.math.abs

/** Direction is solely a chrome hint. Never writes content coordinates or rejects large flings. */
class ScrollIntentTracker {
    enum class Reason { USER_DELTA, BASELINE, SOURCE_OTHER, SOURCE_CHANGED, ZOOM_CHANGED, EPOCH_CHANGED, INACTIVE, INVALID }
    data class Update(val show:Boolean?,val deltaPx:Float,val intentPx:Float,val reason:Reason)
    private var previousY=Float.NaN
    private var previousZoom=Float.NaN
    private var previousUser=false
    private var previousEpoch=Long.MIN_VALUE
    private var accumulated=0f
    fun reset(){previousY=Float.NaN;previousZoom=Float.NaN;previousUser=false;previousEpoch=Long.MIN_VALUE;accumulated=0f}
    fun update(yCss:Float,zoom:Float,user:Boolean,active:Boolean,epoch:Long,thresholdPx:Float):Update {
        if(!active){reset();return Update(null,Float.NaN,0f,Reason.INACTIVE)}
        if(!yCss.isFinite()||!zoom.isFinite()||zoom<=0||!thresholdPx.isFinite()||thresholdPx<=0){reset();return Update(null,Float.NaN,0f,Reason.INVALID)}
        val hadBaseline=previousY.isFinite()
        val sameZoom=previousZoom==zoom
        val delta=if(hadBaseline&&sameZoom&&previousEpoch==epoch)(yCss-previousY)*zoom else Float.NaN
        val reason=when {
            !hadBaseline->Reason.BASELINE
            previousEpoch!=epoch->Reason.EPOCH_CHANGED
            !sameZoom->Reason.ZOOM_CHANGED
            previousUser!=user->Reason.SOURCE_CHANGED
            !user->Reason.SOURCE_OTHER
            !delta.isFinite()->Reason.INVALID
            else->Reason.USER_DELTA
        }
        previousY=yCss;previousZoom=zoom;previousUser=user;previousEpoch=epoch
        if(reason!=Reason.USER_DELTA){accumulated=0f;return Update(null,delta,0f,reason)}
        if(delta*accumulated<0)accumulated=0f
        accumulated+=delta
        val intent=accumulated
        val show=if(abs(intent)>=thresholdPx){accumulated=0f;intent<0} else null
        return Update(show,delta,intent,reason)
    }
}
