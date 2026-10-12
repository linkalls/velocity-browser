package dev.velocity.browser.ui

import kotlin.math.roundToInt

/** Screen pixels throughout. Gecko's dynamic baseline already reserves maxHeight.
 * Thus bottom translation +(H-V), or top translation -(H-V), gives clipping V-H.
 * With maxHeight=0 the full surface baseline instead needs positive occlusion V.
 * Matches Firefox EngineViewClippingBehavior; never subtract H in static mode.
 */
data class ToolbarGeometry(val toolbarTranslation: Float, val contentTranslation: Float, val clipping: Int) {
    companion object {
        fun calculate(height: Int, visible: Float, bottom: Boolean, dynamic: Boolean): ToolbarGeometry {
            require(height > 0 && visible.isFinite())
            val v = visible.coerceIn(0f, height.toFloat())
            val hidden = height - v
            return ToolbarGeometry(if (bottom) hidden else -hidden, if (bottom) 0f else v,
                (if (dynamic) -hidden else v).roundToInt())
        }
    }
}

/** UI motion only; no engine/session ownership. Layout must use visible, never target. */
class ToolbarMotion {
    enum class State { SHOWN, HIDING, HIDDEN, SHOWING, PINNED }
    enum class Source { COMPOSITOR, CONTENT, UI }
    enum class Decision { ACCEPTED, LOCKED, CONTENT_PRIORITY, PINNED, DUPLICATE, HYSTERESIS }
    var decision=Decision.DUPLICATE; private set
    private fun reject(reason:Decision):Boolean {decision=reason;return false}
    var visible = 1f; private set
    var target = 1f; private set
    var state = State.SHOWN; private set
    private var changedAt = Long.MIN_VALUE
    private var compositorAt = Long.MIN_VALUE
    private var pendingUiShow = false
    private var targetOwner = Source.UI
    private var locked = false

    fun lock(value: Boolean) {
        if (locked == value) return
        locked = value
        pendingUiShow = false
        changedAt = Long.MIN_VALUE
        compositorAt = Long.MIN_VALUE
        if (value) { target = 1f; visible = 1f; state = State.PINNED }
        else state = State.SHOWN
    }
    /** Every selected, foreground user sample renews authority, even below 24dp.
     * A slow pan must not lose authority between direction threshold crossings.
     */
    fun observeUserScroll(now:Long){if(!locked)compositorAt=now}
    fun request(show: Boolean, source: Source, now: Long, enginePinned: Boolean = false): Boolean {
        if (locked) return reject(Decision.LOCKED)
        if (source == Source.COMPOSITOR) compositorAt = now
        if (enginePinned) {
            // Do not queue pan/content directions across a selection drag. Only
            // explicit UI restoration can remain pending; never promote an old
            // automatic hide into a fresh UI command when the pin ends.
            if(source==Source.UI&&show)pendingUiShow=true
            state=State.PINNED
            return reject(Decision.PINNED)
        }
        val userTransition=targetOwner==Source.COMPOSITOR&&(state==State.HIDING||state==State.SHOWING)
        // Keep the existing 160ms/80ms bounds. Metadata and transition ownership,
        // rather than a longer magic timeout, arbitrate delayed content requests.
        if(source==Source.CONTENT&&(userTransition||(compositorAt!=Long.MIN_VALUE&&now-compositorAt<160)))return reject(Decision.CONTENT_PRIORITY)
        val next = if (show) 1f else 0f
        if (next == target) return reject(Decision.DUPLICATE)
        if (source != Source.UI && changedAt != Long.MIN_VALUE && now - changedAt < 80) return reject(Decision.HYSTERESIS)
        decision=Decision.ACCEPTED
        changedAt = now; targetOwner=source; target = next; state = if (show) State.SHOWING else State.HIDING
        return true
    }
    /** Freeze the current target without inventing a new UI command. */
    fun pin() { state=State.PINNED }
    fun unpin(now: Long): Boolean {
        if (locked || state != State.PINNED) return false
        state = if (visible == target) if (target == 1f) State.SHOWN else State.HIDDEN else if (target == 1f) State.SHOWING else State.HIDING
        val restore=pendingUiShow;pendingUiShow=false
        val changed=restore&&request(true,Source.UI,now)
        return changed||visible!=target
    }
    fun frame(fraction: Float) {
        visible = fraction.coerceIn(0f, 1f)
        if (!locked && visible == target) state = if (target == 1f) State.SHOWN else State.HIDDEN
    }
    fun reset() { locked = false; pendingUiShow = false;targetOwner=Source.UI; target = 1f; frame(1f); changedAt = Long.MIN_VALUE; compositorAt = Long.MIN_VALUE }
}
