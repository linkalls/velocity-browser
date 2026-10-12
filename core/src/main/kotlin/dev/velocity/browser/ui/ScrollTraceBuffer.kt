package dev.velocity.browser.ui

/** Main-thread numeric ring. No URL, tab ID, strings, HTML, inputs or images accepted. */
class ScrollTraceBuffer(val capacity: Int = 768) {
    init { require(capacity in 1..1000) }
    private val times=LongArray(capacity)
    private val identities=LongArray(capacity)
    private val epochs=LongArray(capacity)
    private val codes=IntArray(capacity*8)
    private val values=FloatArray(capacity*11)
    var size=0; private set
    var total=0L; private set
    private var next=0
    fun clear(){size=0;total=0;next=0;times.fill(0);identities.fill(0);epochs.fill(0);codes.fill(0);values.fill(0f)}
    fun add(timeMs:Long,event:Int,tab:Int,identity:Long,epoch:Long,source:Int=-1,reason:Int=-1,
        state:Int=-1,height:Int=0,maximum:Int=0,flags:Int=0,y:Float=Float.NaN,zoom:Float=Float.NaN,
        delta:Float=Float.NaN,intent:Float=Float.NaN,visible:Float=Float.NaN,target:Float=Float.NaN,
        clipping:Float=Float.NaN,toolbar:Float=Float.NaN,content:Float=Float.NaN,frameGap:Float=Float.NaN,show:Float=Float.NaN) {
        val i=next;times[i]=timeMs;identities[i]=identity;epochs[i]=epoch
        val c=i*8;codes[c]=event;codes[c+1]=tab;codes[c+2]=source;codes[c+3]=reason;codes[c+4]=state;codes[c+5]=height;codes[c+6]=maximum;codes[c+7]=flags
        val v=i*11;values[v]=y;values[v+1]=zoom;values[v+2]=delta;values[v+3]=intent;values[v+4]=visible;values[v+5]=target;values[v+6]=clipping;values[v+7]=toolbar;values[v+8]=content;values[v+9]=frameGap;values[v+10]=show
        next=(next+1)%capacity;size=minOf(size+1,capacity);total++
    }
    /** Only an explicit export/test may allocate CSV. Oldest-to-newest, bounded. */
    fun csv():String=buildString {
        append("uptime_ms,event,anonymous_tab,identity,epoch,source,reason,state,height_px,max_px,flags,y_css,zoom,delta_px,intent_px,visible,target,clipping_px,toolbar_px,content_px,frame_gap_ms,show\n")
        repeat(size){offset->
            val i=(next-size+capacity+offset)%capacity;val c=i*8;val v=i*11
            append(times[i]);append(',');append(codes[c]);append(',');append(codes[c+1]);append(',');append(identities[i]);append(',');append(epochs[i])
            for(j in 2..7){append(',');append(codes[c+j])}
            for(j in 0..10){append(',');append(values[v+j])};append('\n')
        }
    }
}
