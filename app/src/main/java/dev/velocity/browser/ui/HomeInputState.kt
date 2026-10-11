package dev.velocity.browser.ui

/** Process-local native search drafts. Never serialized, including private drafts. */
class HomeInputState {
    private val drafts=mutableMapOf<String,String>()
    fun get(id:String)=drafts[id].orEmpty()
    fun set(id:String,value:String){if(value.isEmpty())drafts.remove(id) else drafts[id]=value.take(4096)}
    fun retain(ids:Set<String>){drafts.keys.retainAll(ids)}
}
