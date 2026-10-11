package dev.velocity.browser

import dev.velocity.browser.tabs.*
import dev.velocity.browser.security.Protection
import dev.velocity.browser.util.Clock
import org.junit.Assert.*
import org.junit.Test

class AdaptiveTabSchedulerTest {
    private class FakeClock(var now: Long = 0) : Clock { override fun elapsedRealtimeMs() = now }
    private val clock = FakeClock()
    private val s = AdaptiveTabScheduler(AdaptiveConfig(), clock)
    private fun create(id: String): List<Command> {
        s.dispatch(Event.CreateTab(id,"https://example.com/$id"))
        val commands = s.dispatch(Event.SelectTab(id))
        s.tab(id)?.identity?.let { s.dispatch(Event.Ready(id,it)) }
        return commands
    }
    // Independent fake-engine proof; a Gecko SessionState callback is never this proof.
    private fun certify(id: String) {
        var t=s.tab(id)!!
        t.protections.toList().forEach { s.dispatch(Event.RiskChanged(id,t.identity!!,it,false)) }
        t=s.tab(id)!!; s.dispatch(Event.SafeCheckpoint(id,t.identity!!,t.epoch,t.revision,t.saveGeneration))
    }
    @Test fun defaultAndProfilesAreSoftTargets() {
        assertEquals(6,s.config.liveTarget);assertEquals(120000L,s.config.discardAfterMs)
        assertEquals(3,TabProfile.MEMORY.config().liveTarget);assertEquals(12,TabProfile.FAST.config().liveTarget)
    }
    @Test fun protectedTabsRemainLiveAtEveryRequestedCount() {
        for (count in listOf(2,3,6,12,20,50,100)) {
            repeat(count) { create("$count-$it") }
            assertEquals("$count-${count-1}",s.selectedId)
            assertNull(s.pendingId);assertTrue(s.tabs.all { it.live })
            assertEquals(1,s.tabs.count { it.state==TabState.ACTIVE })
        }
    }
    @Test fun seventhAdmittedWithSixProtectedIdentitiesUnchanged() {
        repeat(6) { create("t$it"); val t=s.tab("t$it")!!;s.dispatch(Event.RiskChanged(t.id,t.identity!!,Protection.FORM,true)) }
        val before=s.tabs.associate { it.id to it.identity }; val commands=create("seventh")
        assertEquals(7,s.liveCount);assertEquals("seventh",s.selectedId)
        before.forEach { (id,identity)->assertEquals(identity,s.tab(id)!!.identity) }
        assertFalse(commands.any { it is Command.CloseSession || it is Command.ShowEvictionBlockedNotice })
    }
    @Test fun onlyCertifiedHiddenLruIsReclaimedAtTarget() {
        s.dispatch(Event.Configure(AdaptiveConfig(liveTarget=2)))
        create("a");clock.now=10;create("b");certify("a")
        assertTrue(create("c").any { it is Command.CloseSession && it.id=="a" })
        assertEquals(TabState.COLD,s.tab("a")!!.state);assertEquals(2,s.liveCount)
    }
    @Test fun displayedSafePageIsKeptForAllocationRollback() {
        s.dispatch(Event.Configure(AdaptiveConfig(liveTarget=2)))
        create("a");create("b");certify("b")
        assertFalse(create("c").any { it is Command.CloseSession });assertEquals(3,s.liveCount)
    }
    @Test fun allProtectionFlagsPreventPressureEviction() {
        for (risk in Protection.entries) {
            val id=risk.name;create(id);val t=s.tab(id)!!
            s.dispatch(Event.RiskChanged(id,t.identity!!,Protection.UNKNOWN,false))
            s.dispatch(Event.RiskChanged(id,t.identity!!,risk,true))
            val changed=s.tab(id)!!;s.dispatch(Event.SafeCheckpoint(id,t.identity!!,t.epoch,changed.revision,changed.saveGeneration))
            create("after-$id")
            assertFalse(s.dispatch(Event.MemoryPressure()).any { it is Command.CloseSession && it.id==id })
            assertEquals(t.identity,s.tab(id)!!.identity)
        }
    }
    @Test fun adaptiveOffDisablesAutomaticReclamationAndAdmissionLimit() {
        s.adaptiveEnabled=false;create("a");create("b");certify("a")
        repeat(20) { assertFalse(create("t$it").any { it is Command.CloseSession }) }
        assertFalse(s.dispatch(Event.MemoryPressure()).any { it is Command.CloseSession });assertEquals(22,s.liveCount)
    }
    @Test fun idleBoundaryUsesMonotonicTimeAndBackgroundTimerStops() {
        s.dispatch(Event.AppForegroundChanged(true));create("a");clock.now=200;create("b");certify("a")
        assertFalse(s.dispatch(Event.Tick(120199)).any { it is Command.CloseSession })
        s.dispatch(Event.AppForegroundChanged(false));assertFalse(s.dispatch(Event.Tick(120200)).any { it is Command.CloseSession })
        clock.now=120200;assertTrue(s.dispatch(Event.AppForegroundChanged(true)).any { it is Command.CloseSession && it.id=="a" })
    }
    @Test fun pressureReclaimsSafeHiddenInLruOrder() {
        create("a");clock.now=10;create("b");clock.now=20;create("c");certify("a");certify("b")
        assertEquals(listOf("a","b"),s.dispatch(Event.MemoryPressure()).filterIsInstance<Command.CloseSession>().map { it.id })
        assertEquals("c",s.selectedId)
    }
    @Test fun callbackDoesNotProveSafetyOrTriggerPendingRetry() {
        create("a");create("b");val a=s.tab("a")!!
        s.dispatch(Event.SessionStateChanged("a",a.identity!!,a.epoch,"state"));assertFalse(s.tab("a")!!.safelySaved)
        assertFalse(s.dispatch(Event.MemoryPressure()).any { it is Command.CloseSession })
    }
    @Test fun creationFailureRestoresPreviousViewAndKeepsLogicalTabForRetry() {
        create("a");val original=s.tab("a")!!.identity
        s.dispatch(Event.CreateTab("b","https://example.com"));s.dispatch(Event.SelectTab("b"));val b=s.tab("b")!!
        val rollback=s.dispatch(Event.CreationFailed("b",b.identity!!))
        assertEquals("a",s.selectedId);assertEquals(original,s.tab("a")!!.identity);assertEquals("b",s.pendingId)
        assertEquals(TabState.FAILED,s.tab("b")!!.state);assertTrue(rollback.contains(Command.AttachSessionToView("a")))
        assertTrue(s.dispatch(Event.SelectTab("b")).any { it is Command.CreateSession });assertNull(s.pendingId)
        val newer=s.tab("b")!!.identity;s.dispatch(Event.CreationFailed("b",b.identity!!));assertEquals(newer,s.tab("b")!!.identity)
    }
    @Test fun staleIdentityEpochRevisionAndGenerationNeverCloseChangedPage() {
        create("a");create("b");certify("a");val old=s.tab("a")!!
        s.dispatch(Event.MemoryPressure());s.dispatch(Event.SelectTab("a"));assertNotEquals(old.identity,s.tab("a")!!.identity)
        s.dispatch(Event.Crashed("a",old.identity!!));assertTrue(s.tab("a")!!.live)
        val t=s.tab("a")!!;s.dispatch(Event.NavigationStarted("a",t.identity!!,"https://example.com/new"))
        s.dispatch(Event.SessionStateChanged("a",t.identity!!,t.epoch,"late"))
        s.dispatch(Event.SafeCheckpoint("a",t.identity!!,t.epoch,t.revision,t.saveGeneration));assertFalse(s.tab("a")!!.safelySaved)
        assertNull(s.tab("a")!!.snapshot)
    }
    @Test fun timeoutRejectsLateProofAndInputInvalidatesRevision() {
        create("a");create("b");val t=s.tab("a")!!
        s.dispatch(Event.SaveTimedOut("a",t.identity!!,t.saveGeneration))
        s.dispatch(Event.RiskChanged("a",t.identity!!,Protection.UNKNOWN,false));val next=s.tab("a")!!
        s.dispatch(Event.SafeCheckpoint("a",t.identity!!,next.epoch,next.revision,t.saveGeneration));assertFalse(s.tab("a")!!.safelySaved)
        s.dispatch(Event.RiskChanged("a",t.identity!!,Protection.FORM,true))
        s.dispatch(Event.SafeCheckpoint("a",t.identity!!,next.epoch,next.revision,next.saveGeneration));assertFalse(s.tab("a")!!.safelySaved)
    }
    @Test fun readingReplayCannotCertifyOrdinaryOrProtectedPage() {
        create("ordinary");val t=s.tab("ordinary")!!
        s.dispatch(Event.ReplayReady(t.id,t.identity!!,t.epoch,"token"));assertFalse(s.tab(t.id)!!.safelySaved)
        for (risk in Protection.entries.filter { it!=Protection.UNKNOWN }) {
            val id="r-$risk";s.dispatch(Event.CreateTab(id,"https://example.com",readOnly=true));s.dispatch(Event.SelectTab(id));val r=s.tab(id)!!
            s.dispatch(Event.RiskChanged(id,r.identity!!,risk,true));s.dispatch(Event.ReplayReady(id,r.identity!!,r.epoch,"token"))
            assertFalse(s.tab(id)!!.safelySaved)
        }
    }
    @Test fun protectedCloseNeedsConfirmationAndLastCloseCreatesBlank() {
        create("a");assertTrue(s.dispatch(Event.CloseTab("a")).any { it is Command.ConfirmClose });assertTrue(s.tab("a")!!.live)
        s.dispatch(Event.CloseTab("a",true));assertEquals("about:blank",s.tabs.single().url);assertEquals(1,s.liveCount)
        val replacement=s.selectedId;s.dispatch(Event.Ready("a",1));assertEquals(replacement,s.selectedId)
    }
    @Test fun privateOperationsNeverPersistMetadata() {
        val commands=mutableListOf<Command>();commands+=s.dispatch(Event.CreateTab("p","https://example.com",true))
        commands+=s.dispatch(Event.SelectTab("p"));val t=s.tab("p")!!
        commands+=s.dispatch(Event.NavigationStarted("p",t.identity!!,"https://example.com/new"));commands+=s.dispatch(Event.CloseTab("p",true))
        assertFalse(commands.contains(Command.PersistTabMetadata))
    }
    @Test fun restoreOnlySelectedAmongHundredLogicalTabs() {
        repeat(100) { s.dispatch(Event.CreateTab("t$it","https://example.com/$it")) }
        assertEquals(0,s.liveCount);assertEquals(1,s.dispatch(Event.SelectTab("t50")).filterIsInstance<Command.CreateSession>().size)
        assertEquals(1,s.liveCount);assertEquals(100,s.tabs.size)
    }
    @Test fun configureLowerTargetDoesNotForceEvictOrBlock() {
        repeat(12) { create("t$it") };s.dispatch(Event.Configure(TabProfile.MEMORY.config()))
        assertEquals(12,s.liveCount);assertFalse(create("next").any { it is Command.CloseSession });assertEquals(13,s.liveCount)
    }
    @Test fun coldCloseDoesNotTouchDisplayedSession() {
        create("a");s.dispatch(Event.CreateTab("cold","https://example.com"))
        assertFalse(s.dispatch(Event.CloseTab("cold")).any { it is Command.CloseSession || it is Command.CreateSession });assertEquals("a",s.selectedId)
    }
}
