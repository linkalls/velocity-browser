package dev.velocity.browser.ui

import org.junit.Assert.*
import org.junit.Test

class ToolbarMotionTest {
    @Test fun geometryMatrixAcrossPlacementModeDensityAndLargeFont() {
        // Expected (shown, half, hidden) clipping, in px: dynamic (0,-H/2,-H),
        // static (H,H/2,0). Translation independent of dynamic baseline.
        for (dp in listOf(56,72)) for (density in listOf(1,2,3)) {
            val h=dp*density
            for (bottom in listOf(false,true)) for (dynamic in listOf(false,true)) {
                for (v in listOf(h.toFloat(),h/2f,0f)) {
                    val g=ToolbarGeometry.calculate(h,v,bottom,dynamic)
                    assertEquals(if(dynamic)(v-h).toInt() else v.toInt(),g.clipping)
                    assertEquals(if(bottom)h-v else v-h,g.toolbarTranslation,0f)
                    assertEquals(if(bottom)0f else v,g.contentTranslation,0f)
                }
            }
        }
    }
    @Test fun roundingClampAndRotationDoNotReuseOldHeight() {
        assertEquals(-1,ToolbarGeometry.calculate(168,166.5f,true,true).clipping)
        assertEquals(167,ToolbarGeometry.calculate(168,166.5f,false,false).clipping)
        assertEquals(-168,ToolbarGeometry.calculate(168,-9f,true,true).clipping)
        assertEquals(0,ToolbarGeometry.calculate(168,200f,true,true).clipping)
        assertEquals(-108,ToolbarGeometry.calculate(216,108f,false,true).clipping)
    }
    @Test fun duplicateSignalsAndLayoutRetainIntermediatePosition() {
        val m=ToolbarMotion()
        assertTrue(m.request(false,ToolbarMotion.Source.COMPOSITOR,100))
        m.frame(.6f)
        repeat(20){assertFalse(m.request(false,ToolbarMotion.Source.COMPOSITOR,110+it.toLong()))}
        assertEquals(.6f,m.visible,0f);assertEquals(ToolbarMotion.State.HIDING,m.state)
        m.frame(0f);assertEquals(ToolbarMotion.State.HIDDEN,m.state)
    }
    @Test fun oppositeSourcesAndFastDirectionChangesDoNotOscillate() {
        val m=ToolbarMotion()
        m.request(false,ToolbarMotion.Source.COMPOSITOR,100);m.frame(.4f)
        assertFalse(m.request(true,ToolbarMotion.Source.CONTENT,110))
        assertFalse(m.request(true,ToolbarMotion.Source.COMPOSITOR,140))
        assertTrue(m.request(true,ToolbarMotion.Source.COMPOSITOR,200))
        assertEquals(.4f,m.visible,0f);assertEquals(1f,m.target,0f)
    }
    @Test fun imeModalAndSelectionPinInterruptAndRecoverSafely() {
        val m=ToolbarMotion();m.request(false,ToolbarMotion.Source.COMPOSITOR,100);m.frame(.5f)
        assertFalse(m.request(true,ToolbarMotion.Source.CONTENT,400,true));assertEquals(.5f,m.visible,0f)
        assertTrue(m.unpin(410));assertEquals(0f,m.target,0f)
        assertTrue(m.request(true,ToolbarMotion.Source.COMPOSITOR,500));assertEquals(1f,m.target,0f)
        m.lock(true);assertEquals(ToolbarMotion.State.PINNED,m.state);assertEquals(1f,m.visible,0f)
        assertFalse(m.request(false,ToolbarMotion.Source.COMPOSITOR,500));m.lock(false)
        assertEquals(ToolbarMotion.State.SHOWN,m.state)
        assertTrue(m.request(false,ToolbarMotion.Source.COMPOSITOR,510))
    }
    @Test fun tabSwitchAndCancellationResetAllOldIntent() {
        val m=ToolbarMotion();m.request(false,ToolbarMotion.Source.COMPOSITOR,100);m.frame(.25f)
        m.reset();assertEquals(1f,m.visible,0f);assertEquals(1f,m.target,0f)
        assertTrue(m.request(false,ToolbarMotion.Source.CONTENT,101));m.frame(0f)
        assertEquals(ToolbarMotion.State.HIDDEN,m.state)
    }
    @Test fun delayedContentCannotReverseUnfinishedCompositorAnimation() {
        val m=ToolbarMotion();m.request(false,ToolbarMotion.Source.COMPOSITOR,100);m.frame(.6f)
        assertFalse(m.request(true,ToolbarMotion.Source.CONTENT,300))
        assertEquals(0f,m.target,0f);assertEquals(.6f,m.visible,0f)
    }
    @Test fun unpinCannotPromoteOldAutomaticHideToAUiCommand() {
        val m=ToolbarMotion();m.request(false,ToolbarMotion.Source.COMPOSITOR,100,true)
        assertFalse(m.unpin(1000));assertEquals(1f,m.target,0f);assertEquals(1f,m.visible,0f)
        m.request(false,ToolbarMotion.Source.COMPOSITOR,1100);m.frame(.5f)
        m.pin();assertTrue(m.unpin(1200));assertEquals(0f,m.target,0f);assertEquals(.5f,m.visible,0f)
        m.pin();m.request(true,ToolbarMotion.Source.UI,1300,true)
        assertTrue(m.unpin(1400));assertEquals(1f,m.target,0f);assertEquals(.5f,m.visible,0f)
    }

    @Test fun slowPanRenewsAuthorityWithoutAnotherDistanceThreshold() {
        val m=ToolbarMotion();m.request(false,ToolbarMotion.Source.COMPOSITOR,100);m.frame(0f)
        m.observeUserScroll(290)
        assertFalse(m.request(true,ToolbarMotion.Source.CONTENT,300))
        assertTrue(m.request(true,ToolbarMotion.Source.COMPOSITOR,310))
        m.frame(1f);assertFalse(m.request(false,ToolbarMotion.Source.CONTENT,400))
        assertTrue(m.request(false,ToolbarMotion.Source.CONTENT,500))
    }

}
