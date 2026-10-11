package dev.velocity.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velocity.browser.block.BlockController
import dev.velocity.browser.engine.GeckoRuntimeHolder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 明示runner引数がある専用試験端末だけで公式XPIを取得・導入。通常CIはskip。 */
@RunWith(AndroidJUnit4::class)
class AdguardIntegrationTest {
    @RequiresExternalFixture
    @Test fun officialSignedXpiInstallModeSwitchAndPrivateExclusion() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Official XPI install not opted in: UNVERIFIED", InstrumentationRegistry.getArguments().getString("adguardInstallConsent") == "true")
        val app = inst.targetContext.applicationContext as VelocityApp
        val device = UiDevice.getInstance(inst)
        inst.targetContext.startActivity(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 120000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ok = false; inst.runOnMainSync { ok = condition() }; if (ok) return
                SystemClock.sleep(100)
            }; fail("AdGuard operation timed out")
        }
        waitFor { !app.blocker.busy && app.blocker.status != "NOT_INITIALIZED" }
        var existed = false; var previous = BlockController.Mode.ETP_ONLY
        inst.runOnMainSync { existed = app.blocker.hasAdguard(); previous = app.blocker.mode }
        assumeTrue("Dedicated clean profile required; do not replace existing XPI", !existed)
        try {
            inst.runOnMainSync { app.blocker.installAdguard(app.coordinator.host!!.activity) }
            assertTrue(device.wait(Until.hasObject(By.text("AdGuardの権限確認")), 120000))
            device.findObject(By.text("許可")).click()
            waitFor { !app.blocker.busy && app.blocker.hasAdguard() && app.blocker.mode == BlockController.Mode.ADGUARD }
            val latch = java.util.concurrent.CountDownLatch(1)
            var extension: org.mozilla.geckoview.WebExtension? = null
            inst.runOnMainSync {
                GeckoRuntimeHolder.get(app).webExtensionController.list().accept({ list -> extension = list?.find { it.id == BlockController.ADGUARD_ID }; latch.countDown() }, { latch.countDown() })
            }
            assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(extension)
            assertEquals("5.5.2.3", extension!!.metaData.version)
            assertTrue(extension!!.metaData.enabled)
            assertFalse(extension!!.metaData.allowedInPrivateBrowsing)
            // Signed-XPI options: two protected tabs, cancel, retry, explicit close and reuse.
            var target = ""; var other = ""; var otherIdentity: Long? = null
            inst.runOnMainSync {
                assertFalse(app.blocker.enabled(false)); assertFalse(app.blocker.enabled(true))
                app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) }
                other = app.coordinator.scheduler.selectedId!!
                otherIdentity = app.coordinator.scheduler.tab(other)!!.identity
                app.coordinator.createTab()
                app.blocker.openAdguardOptions()
                target = app.coordinator.scheduler.pendingId!!
                assertTrue(app.coordinator.isOptionsPending(target))
                assertEquals(2,app.coordinator.scheduler.liveCount)
                app.coordinator.cancelPending(target)
                assertNull(app.coordinator.scheduler.pendingId)
                app.blocker.openAdguardOptions()
                assertEquals(target,app.coordinator.scheduler.pendingId)
                val before = app.coordinator.scheduler.tabs.map { it.id }
                val base = extension!!.metaData.baseUrl
                assertNull(app.coordinator.createExtensionTab(base + "options.html",base,true))
                assertEquals("failed delegated load leaves no phantom tab",before,app.coordinator.scheduler.tabs.map { it.id })
                app.blocker.openAdguardOptions()
            }
            assertTrue(device.wait(Until.hasObject(By.text("AdGuardの詳細設定を開く")),10000))
            inst.runOnMainSync { app.coordinator.close(app.coordinator.scheduler.tabs.first { it.live && it.id != other }.id) }
            assertTrue(device.wait(Until.hasObject(By.text("このタブを閉じる")),10000))
            device.findObject(By.text("このタブを閉じる")).click()
            waitFor { app.coordinator.scheduler.selectedId == target && app.coordinator.scheduler.tab(target)?.live == true }
            inst.runOnMainSync {
                val options = app.coordinator.scheduler.tab(target)!!
                assertTrue(app.blocker.extensionPageAllowed(options.url,extension!!.metaData.baseUrl))
                val count = app.coordinator.scheduler.tabs.size
                app.coordinator.dispatch(dev.velocity.browser.tabs.Event.LocationChanged(target,options.identity!!,options.url + "#settings"))
                app.blocker.openAdguardOptions()
                assertEquals(target,app.coordinator.scheduler.selectedId)
                assertEquals(count,app.coordinator.scheduler.tabs.size)
                assertEquals(otherIdentity,app.coordinator.scheduler.tab(other)!!.identity)
                assertEquals(2,app.coordinator.scheduler.liveCount)
                app.blocker.setMode(BlockController.Mode.ETP_ONLY)
            }
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("DISABLED") }
            // Gecko installの成功・mode/private範囲だけ検証。遮断率・更新・Popupは別ゲート。
        } finally {
            waitFor { !app.blocker.busy }
            inst.runOnMainSync { if (app.blocker.hasAdguard()) app.blocker.removeAdguard() }
            waitFor { !app.blocker.busy && !app.blocker.hasAdguard() }
            inst.runOnMainSync { app.blocker.setMode(previous) }
        }
    }

    /** AdGuardの設定画面で専用fixture規則を保存済みの場合だけ動かす。 */
    @RequiresExternalFixture
    @Test fun configuredAdguardBlocksHttpsFixtureWithoutReloadingDraftOnModeChange() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val origin = InstrumentationRegistry.getArguments().getString("adguardFixtureOrigin")
        assumeTrue("Configured AdGuard HTTPS fixture absent: UNVERIFIED", origin != null)
        require(origin!!.matches(Regex("https://site\\.velocity\\.test(?::[0-9]+)?")))
        val app = inst.targetContext.applicationContext as VelocityApp
        val device = UiDevice.getInstance(inst)
        inst.targetContext.startActivity(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ok = false; inst.runOnMainSync { ok = condition() }; if (ok) return
                SystemClock.sleep(100)
            }; fail("AdGuard fixture setup or mode switch failed")
        }
        waitFor { !app.blocker.busy && app.blocker.status != "NOT_INITIALIZED" }
        var installed = false; var previous = BlockController.Mode.ETP_ONLY
        inst.runOnMainSync { installed = app.blocker.hasAdguard(); previous = app.blocker.mode }
        assumeTrue("Preinstalled XPI with ||ads.velocity.test^ user rule required", installed)
        try {
            inst.runOnMainSync {
                app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id, true) }
                app.blocker.setMode(BlockController.Mode.ETP_ONLY)
            }
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("DISABLED") }
            inst.runOnMainSync { app.coordinator.createTab("$origin/") }
            assertTrue(device.wait(Until.hasObject(By.text("AD_ALLOWED CONTROL_OK")), 30000))
            inst.runOnMainSync { app.blocker.setMode(BlockController.Mode.ADGUARD) }
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("ADGUARD_ENABLED") }
            inst.runOnMainSync { app.coordinator.reload() }
            assertTrue(device.wait(Until.hasObject(By.text("AD_FAILED CONTROL_OK")), 30000))
            val input = device.findObjects(By.clazz("android.widget.EditText")).first { it.resourceName != "dev.velocity.browser:id/address" }
            input.text = "ADGUARD_DRAFT_PRESERVED"
            var identity: Long? = null; var id = ""
            inst.runOnMainSync { id = app.coordinator.scheduler.selectedId!!; identity = app.coordinator.scheduler.tab(id)!!.identity; app.blocker.setMode(BlockController.Mode.ETP_ONLY) }
            waitFor { !app.blocker.busy }
            assertEquals("ADGUARD_DRAFT_PRESERVED", input.text)
            inst.runOnMainSync { assertEquals(identity, app.coordinator.scheduler.tab(id)!!.identity); assertTrue(app.coordinator.scheduler.liveCount <= 2) }
        } finally {
            waitFor { !app.blocker.busy }; inst.runOnMainSync { app.blocker.setMode(previous) }
        }
    }
}
