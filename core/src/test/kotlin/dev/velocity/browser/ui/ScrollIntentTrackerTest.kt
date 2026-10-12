package dev.velocity.browser.ui

import org.junit.Assert.*
import org.junit.Test

class ScrollIntentTrackerTest {
    @Test fun scriptToUserBoundaryCannotInventDirectionFromAJump() {
        val t=ScrollIntentTracker()
        t.update(2000f,1f,false,true,1,24f)
        val first=t.update(100f,1f,true,true,1,24f)
        assertNull(first.show);assertEquals(ScrollIntentTracker.Reason.SOURCE_CHANGED,first.reason)
        assertEquals(false,t.update(130f,1f,true,true,1,24f).show)
    }
    @Test fun pinchZoomMustNotMultiplyOldPositionIntoAFakePan() {
        val t=ScrollIntentTracker();t.update(1000f,1f,true,true,1,24f)
        assertNull(t.update(1000f,2f,true,true,1,24f).show)
        assertEquals(true,t.update(980f,2f,true,true,1,24f).show)
        val changed=t.update(980f,1f,true,true,1,24f);assertNull(changed.show);assertEquals(ScrollIntentTracker.Reason.ZOOM_CHANGED,changed.reason)
    }
    @Test fun epochTabAndForegroundBoundariesRebaseWithoutDroppingNextRealPan() {
        val t=ScrollIntentTracker();t.update(100f,1f,true,true,1,24f)
        assertNull(t.update(900f,1f,true,true,2,24f).show)
        t.update(5000f,1f,true,false,2,24f)
        assertNull(t.update(100f,1f,true,true,2,24f).show)
        assertEquals(false,t.update(130f,1f,true,true,2,24f).show)
        t.reset();assertNull(t.update(2f,1f,true,true,2,24f).show)
    }
    @Test fun fastLargeFlingRemainsAValidUserMovement() {
        val t=ScrollIntentTracker();t.update(0f,3f,true,true,1,72f)
        val u=t.update(100000f,3f,true,true,1,72f)
        assertEquals(false,u.show);assertEquals(300000f,u.deltaPx,0f)
        assertEquals(ScrollIntentTracker.Reason.USER_DELTA,u.reason)
    }
    @Test fun smallDirectionReversalsDoNotInheritOppositeIntent() {
        val t=ScrollIntentTracker();t.update(100f,1f,true,true,1,24f)
        assertNull(t.update(120f,1f,true,true,1,24f).show)
        assertNull(t.update(115f,1f,true,true,1,24f).show)
        assertEquals(true,t.update(95f,1f,true,true,1,24f).show)
        assertEquals(false,t.update(120f,1f,true,true,1,24f).show)
    }
    @Test fun invalidTelemetryCannotCreateAnInfiniteOrNanDirection() {
        val t=ScrollIntentTracker();assertNull(t.update(Float.NaN,1f,true,true,1,24f).show)
        assertNull(t.update(100f,0f,true,true,1,24f).show)
        assertNull(t.update(100f,1f,true,true,1,24f).show)
        assertEquals(false,t.update(124f,1f,true,true,1,24f).show)
    }
}
