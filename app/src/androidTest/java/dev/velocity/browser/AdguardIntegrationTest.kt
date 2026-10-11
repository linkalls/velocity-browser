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
                var ok = false; inst.onMainForTest { ok = condition() }; if (ok) return
                SystemClock.sleep(100)
            }; fail("AdGuard operation timed out")
        }
        waitFor { !app.blocker.busy && app.blocker.status != "NOT_INITIALIZED" }
        var existed = false; var previous = BlockController.Mode.ETP_ONLY
        inst.onMainForTest { existed = app.blocker.hasAdguard(); previous = app.blocker.mode }
        assumeTrue("Dedicated clean profile required; do not replace existing XPI", !existed)
        try {
            inst.onMainForTest { app.blocker.installAdguard(app.coordinator.host!!.activity) }
            assertTrue(device.wait(Until.hasObject(By.text("AdGuardの権限確認")), 120000))
            device.findObject(By.text("許可")).click()
            waitFor { !app.blocker.busy && app.blocker.hasAdguard() && app.blocker.mode == BlockController.Mode.ADGUARD }
            val latch = java.util.concurrent.CountDownLatch(1)
            var extension: org.mozilla.geckoview.WebExtension? = null
            inst.onMainForTest {
                GeckoRuntimeHolder.get(app).webExtensionController.list().accept({ list -> extension = list?.find { it.id == BlockController.ADGUARD_ID }; latch.countDown() }, { latch.countDown() })
            }
            assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(extension)
            assertEquals("5.5.2.3", extension!!.metaData.version)
            assertTrue(extension!!.metaData.enabled)
            assertFalse(extension!!.metaData.allowedInPrivateBrowsing)
            // Protected tabs no longer block options; reuse the same options session.
            var target = ""; var other = ""; var otherIdentity: Long? = null
            inst.onMainForTest {
                assertFalse(app.blocker.enabled(false));assertFalse(app.blocker.enabled(true))
                app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) }
                other=app.coordinator.scheduler.selectedId!!;otherIdentity=app.coordinator.scheduler.tab(other)!!.identity
                repeat(6) { app.coordinator.createTab() };app.blocker.openAdguardOptions()
                target=app.coordinator.scheduler.selectedId!!
                assertNull(app.coordinator.scheduler.pendingId);assertTrue(app.coordinator.scheduler.liveCount>=7)
                val options=app.coordinator.scheduler.tab(target)!!
                assertTrue(app.blocker.extensionPageAllowed(options.url,extension!!.metaData.baseUrl))
                val count=app.coordinator.scheduler.tabs.size;app.blocker.openAdguardOptions()
                assertEquals(target,app.coordinator.scheduler.selectedId);assertEquals(count,app.coordinator.scheduler.tabs.size)
                assertEquals(otherIdentity,app.coordinator.scheduler.tab(other)!!.identity)
                app.blocker.setMode(BlockController.Mode.ETP_ONLY)
            }
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("DISABLED") }
            // Gecko installの成功・mode/private範囲だけ検証。遮断率・更新・Popupは別ゲート。
        } finally {
            waitFor { !app.blocker.busy }
            inst.onMainForTest { if (app.blocker.hasAdguard()) app.blocker.removeAdguard() }
            waitFor { !app.blocker.busy && !app.blocker.hasAdguard() }
            inst.onMainForTest { app.blocker.setMode(previous) }
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
                var ok = false; inst.onMainForTest { ok = condition() }; if (ok) return
                SystemClock.sleep(100)
            }; fail("AdGuard fixture setup or mode switch failed")
        }
        waitFor { !app.blocker.busy && app.blocker.status != "NOT_INITIALIZED" }
        var installed = false; var previous = BlockController.Mode.ETP_ONLY
        inst.onMainForTest { installed = app.blocker.hasAdguard(); previous = app.blocker.mode }
        assumeTrue("Preinstalled XPI with ||ads.velocity.test^ user rule required", installed)
        try {
            inst.onMainForTest {
                app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id, true) }
                app.blocker.setMode(BlockController.Mode.ETP_ONLY)
            }
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("DISABLED") }
            inst.onMainForTest { app.coordinator.createTab("$origin/") }
            assertTrue(device.wait(Until.hasObject(By.text("AD_ALLOWED CONTROL_OK")), 30000))
            inst.onMainForTest { app.blocker.setMode(BlockController.Mode.ADGUARD) }
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("ADGUARD_ENABLED") }
            inst.onMainForTest { app.coordinator.reload() }
            assertTrue(device.wait(Until.hasObject(By.text("AD_FAILED CONTROL_OK")), 30000))
            val input = device.findObjects(By.clazz("android.widget.EditText")).first { it.resourceName != "dev.velocity.browser:id/address" }
            input.text = "ADGUARD_DRAFT_PRESERVED"
            var identity: Long? = null; var id = ""
            inst.onMainForTest { id = app.coordinator.scheduler.selectedId!!; identity = app.coordinator.scheduler.tab(id)!!.identity; app.blocker.setMode(BlockController.Mode.ETP_ONLY) }
            waitFor { !app.blocker.busy }
            assertEquals("ADGUARD_DRAFT_PRESERVED", input.text)
            inst.onMainForTest { assertEquals(identity, app.coordinator.scheduler.tab(id)!!.identity); assertNull(app.coordinator.scheduler.pendingId) }
        } finally {
            waitFor { !app.blocker.busy }; inst.onMainForTest { app.blocker.setMode(previous) }
        }
    }
}
