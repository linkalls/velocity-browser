package dev.velocity.browser

import dev.velocity.browser.tabs.*
import dev.velocity.browser.security.*
import dev.velocity.browser.util.Clock
import org.junit.Assert.*
import org.junit.Test

class AdaptiveTabSchedulerTest {
    private class FakeClock(var now: Long = 0) : Clock { override fun elapsedRealtimeMs() = now }
    private val clock = FakeClock()
    private val s = AdaptiveTabScheduler(AdaptiveConfig(), clock)
    private fun create(id: String, privateMode: Boolean = false): List<Command> {
        s.dispatch(Event.CreateTab(id, "https://example.com/", privateMode))
        val commands = s.dispatch(Event.SelectTab(id))
        s.tab(id)?.identity?.let { s.dispatch(Event.Ready(id, it)) }
        return commands
    }
    /** テストのfake engineが保存・リスク不在を独立に証明する。Geckoの通知とは異なる。 */
    private fun certify(id: String) {
        var t = s.tab(id)!!
        t.protections.toList().forEach { s.dispatch(Event.RiskChanged(id, t.identity!!, it, false)) }
        t = s.tab(id)!!
        s.dispatch(Event.SafeCheckpoint(id, t.identity!!, t.epoch, t.revision, t.saveGeneration))
    }
    private fun foreground() = s.dispatch(Event.AppForegroundChanged(true))
    @Test fun firstTabActive() { create("a"); assertEquals(TabState.ACTIVE, s.tab("a")!!.state); assertEquals(1, s.liveCount) }
    @Test fun secondTabOneActiveOneWarm() {
        create("a"); create("b"); certify("a")
        assertEquals(TabState.WARM, s.tab("a")!!.state); assertEquals(TabState.ACTIVE, s.tab("b")!!.state); assertEquals(2, s.liveCount)
    }
    @Test fun thirdTabEvictsSavedLru() {
        create("a"); clock.now = 10; create("b"); certify("a")
        val commands = create("c")
        assertTrue(commands.any { it is Command.CloseSession && it.id == "a" }); assertEquals(TabState.COLD, s.tab("a")!!.state); assertEquals(2, s.liveCount)
    }
    @Test fun unsavedThirdKeepsDisplayedPageAndBlocks() {
        create("a"); create("b"); val commands = create("c")
        assertFalse(commands.any { it is Command.CloseSession }); assertTrue(commands.any { it is Command.ShowEvictionBlockedNotice })
        assertEquals("b", s.selectedId); assertEquals(TabState.COLD, s.tab("c")!!.state)
    }
    @Test fun callbackIsNotFlushAcknowledgment() {
        create("a"); create("b"); val t = s.tab("a")!!
        s.dispatch(Event.SessionStateChanged("a", t.identity!!, t.epoch, "snapshot"))
        assertFalse(s.tab("a")!!.safelySaved); assertTrue(create("c").any { it is Command.ShowEvictionBlockedNotice })
    }
    @Test fun elapsed120SecondsIsCandidateBoundary() {
        foreground(); create("a"); clock.now = 200; create("b"); certify("a")
        clock.now = 120199; assertFalse(s.dispatch(Event.Tick(clock.now)).any { it is Command.CloseSession })
        clock.now = 120200; assertTrue(s.dispatch(Event.Tick(clock.now)).any { it is Command.CloseSession && it.id == "a" })
        assertEquals(1, s.liveCount)
    }
    @Test fun everyRiskPreventsAutomaticEviction() {
        for (risk in Protection.entries) {
            val test = AdaptiveTabScheduler(AdaptiveConfig(), clock)
            test.dispatch(Event.AppForegroundChanged(true))
            test.dispatch(Event.CreateTab("a", "https://example.com/")); test.dispatch(Event.SelectTab("a"))
            val a = test.tab("a")!!
            test.dispatch(Event.RiskChanged("a", a.identity!!, Protection.UNKNOWN, false))
            test.dispatch(Event.RiskChanged("a", a.identity!!, risk, true))
            test.dispatch(Event.SafeCheckpoint("a", a.identity!!, a.epoch, test.tab("a")!!.revision, 0))
            test.dispatch(Event.CreateTab("b", "https://example.com/")); test.dispatch(Event.SelectTab("b"))
            assertFalse("$risk", test.dispatch(Event.MemoryPressure()).any { it is Command.CloseSession })
            test.dispatch(Event.CreateTab("c", "https://example.com/"))
            assertTrue("$risk", test.dispatch(Event.SelectTab("c")).any { it is Command.ShowEvictionBlockedNotice })
            assertEquals(2, test.liveCount)
        }
    }
    @Test fun protectedCloseRequiresExplicitConfirmation() {
        create("a"); assertTrue(s.dispatch(Event.CloseTab("a")).any { it is Command.ConfirmClose })
        assertEquals(1, s.liveCount); assertTrue(s.dispatch(Event.CloseTab("a", true)).any { it is Command.CloseSession }); assertEquals(0, s.liveCount)
    }
    @Test fun memoryPressureReclaimsOnlySafeHidden() {
        create("a"); create("b"); certify("a")
        assertTrue(s.dispatch(Event.MemoryPressure()).any { it is Command.CloseSession && it.id == "a" }); assertEquals("b", s.selectedId)
    }
    @Test fun staleIdentityCannotAffectRecreatedTab() {
        create("a"); val identity = s.tab("a")!!.identity!!; create("b"); certify("a"); create("c"); certify("b")
        s.dispatch(Event.SelectTab("a")); val newer = s.tab("a")!!.identity
        assertNotEquals(identity, newer)
        s.dispatch(Event.SessionStateChanged("a", identity, 0, "old"))
        s.dispatch(Event.Crashed("a", identity)); assertNull(s.tab("a")!!.snapshot); assertEquals(newer, s.tab("a")!!.identity)
    }
    @Test fun newNavigationInvalidatesOldSnapshotAndCertificate() {
        create("a"); certify("a"); val t = s.tab("a")!!
        s.dispatch(Event.SessionStateChanged("a", t.identity!!, t.epoch, "old"))
        s.dispatch(Event.NavigationStarted("a", t.identity!!, "https://example.com/new"))
        s.dispatch(Event.SessionStateChanged("a", t.identity!!, t.epoch, "late"))
        s.dispatch(Event.SafeCheckpoint("a", t.identity!!, t.epoch, t.revision, t.saveGeneration))
        assertNull(s.tab("a")!!.snapshot); assertFalse(s.tab("a")!!.safelySaved)
    }
    @Test fun inputRiskInvalidatesSaveGenerationEvidence() {
        create("a"); val t = s.tab("a")!!
        s.dispatch(Event.RiskChanged("a", t.identity!!, Protection.UNKNOWN, false)); val before = s.tab("a")!!
        s.dispatch(Event.RiskChanged("a", t.identity!!, Protection.FORM, true))
        s.dispatch(Event.SafeCheckpoint("a", t.identity!!, before.epoch, before.revision, before.saveGeneration)); assertFalse(s.tab("a")!!.safelySaved)
    }
    @Test fun foregroundTimerStopsInBackgroundAndReevaluatesOnResume() {
        foreground(); create("a"); create("b"); certify("a")
        assertTrue(s.dispatch(Event.AppForegroundChanged(false)).filterIsInstance<Command.SetSessionActive>().none { it.active })
        clock.now = 500000; assertFalse(s.dispatch(Event.Tick(clock.now)).any { it is Command.CloseSession })
        assertTrue(foreground().any { it is Command.CloseSession })
    }
    @Test fun privateNeverGeneratesPersistCommands() {
        val created = s.dispatch(Event.CreateTab("p", "https://example.com/", true))
        val selected = s.dispatch(Event.SelectTab("p")); val t = s.tab("p")!!
        val moved = s.dispatch(Event.NavigationStarted("p", t.identity!!, "https://example.com/new"))
        assertFalse((created + selected + moved + s.dispatch(Event.CloseTab("p", true))).contains(Command.PersistTabMetadata))
    }
    @Test fun hundredTabsRepeatedSelectionsNeverLeakOrDuplicate() {
        foreground()
        for (n in 0 until 100) {
            s.tabs.filter { it.live }.forEach { certify(it.id) }
            create("t$n"); assertTrue(s.liveCount <= 2)
        }
        for (n in 0 until 100) {
            s.tabs.filter { it.live }.forEach { certify(it.id) }
            s.dispatch(Event.SelectTab("t$n")); assertTrue(s.liveCount <= 2)
        }
        assertEquals(100, s.tabs.map { it.id }.toSet().size)
        s.tabs.filterNot { it.live }.forEach { assertNull(it.identity) }
        for (n in 0 until 100) s.dispatch(Event.CloseTab("t$n", true))
        assertEquals(0, s.liveCount); assertTrue(s.tabs.isEmpty())
    }
    @Test fun failedRestoreFallsBackToUrlAndCrashIsRetryable() {
        create("a"); val t = s.tab("a")!!
        assertTrue(s.dispatch(Event.RestoreFailed("a", t.identity!!)).contains(Command.FallbackReload("a", t.url)))
        s.dispatch(Event.Crashed("a", t.identity!!)); assertEquals(TabState.FAILED, s.tab("a")!!.state)
        assertTrue(s.dispatch(Event.SelectTab("a")).any { it is Command.CreateSession }); assertEquals(1, s.liveCount)
    }
    @Test fun duplicateEventsAreIdempotent() {
        create("a"); assertTrue(s.dispatch(Event.SelectTab("a")).isEmpty())
        s.dispatch(Event.CreateTab("a", "https://other.example/")); assertEquals(1, s.tabs.size)
        s.dispatch(Event.CloseTab("a", true)); assertTrue(s.dispatch(Event.CloseTab("a", true)).isEmpty())
    }
    @Test fun adaptiveOffStillEnforcesHardSafetyLimit() {
        s.adaptiveEnabled = false; create("a"); create("b"); assertTrue(create("c").any { it is Command.ShowEvictionBlockedNotice }); assertEquals(2, s.liveCount)
    }
}
