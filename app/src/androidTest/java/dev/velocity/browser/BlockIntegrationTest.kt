package dev.velocity.browser

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velocity.browser.block.BlockController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockIntegrationTest {
    @Test fun privateRulesAndExceptionsAreNotPersistent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        // 単独controllerでprivate設定のみ変更。Runtimeや既存通常設定は変更しない。
        val block = BlockController(context)
        inst.runOnMainSync {
            block.setUserRules("||private-only.example^", true)
            block.toggleException("https://private-only.example", true)
            assertTrue(block.isException("https://private-only.example", true))
            val stored = context.getSharedPreferences("block_settings", 0)
            assertFalse(stored.getString("user", "")!!.contains("private-only.example"))
            assertFalse(stored.getStringSet("exceptions", emptySet())!!.contains("https://private-only.example"))
            block.resetPrivate()
            assertEquals("", block.userRules(true)); assertFalse(block.isException("https://private-only.example", true))
        }
    }
    /** 専用信頼済みTLS fixtureをrunner引数で指定。未指定はskipであり成功ではない。 */
    @Test fun liteHttpsBlockingAndExceptionPreserveTwoLiveLimit() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("blockFixtureOrigin")
        assumeTrue("HTTPS fixture absent: UNVERIFIED", origin != null)
        require(origin!!.matches(Regex("https://site\\.velocity\\.test(?::[0-9]+)?")))
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
        val app = context.applicationContext as VelocityApp
        fun waitFor(condition: () -> Boolean) {
            val limit = SystemClock.elapsedRealtime() + 30000
            while (SystemClock.elapsedRealtime() < limit) {
                var ok = false; inst.runOnMainSync { ok = condition() }; if (ok) return
                SystemClock.sleep(100)
            }; fail("blocker did not reach expected state: ${app.blocker.status}")
        }
        waitFor { !app.blocker.busy && app.blocker.status != "NOT_INITIALIZED" }
        var previousUser = ""; var previousMode = BlockController.Mode.ETP_ONLY; var hadException = false
        inst.runOnMainSync {
            previousUser = app.blocker.userRules(false); previousMode = app.blocker.mode
            hadException = app.blocker.isException(origin, false)
            if (hadException) app.blocker.toggleException(origin, false)
            app.coordinator.scheduler.tabs.toList().forEach { app.coordinator.close(it.id, true) }
            app.blocker.setUserRules("||ads.velocity.test^\nsite.velocity.test##.ad-slot", false)
            app.blocker.setMode(BlockController.Mode.ETP_ONLY)
        }
        try {
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("DISABLED") }
            inst.runOnMainSync { app.coordinator.createTab("$origin/") }
            // 必ず先に同じ広告originのTLS/DNS/HTTP到達成功を証明。
            assertTrue(device.wait(Until.hasObject(By.text("AD_ALLOWED CONTROL_OK")), 30000))
            inst.runOnMainSync { app.blocker.setMode(BlockController.Mode.LITE) }
            waitFor { app.blocker.status.startsWith("READY_CONFIG_ACK") }
            inst.runOnMainSync { app.coordinator.reload() }
            assertTrue(device.wait(Until.hasObject(By.text("AD_FAILED CONTROL_OK")), 30000))
            inst.runOnMainSync { assertTrue(app.coordinator.scheduler.liveCount <= 2); app.blocker.toggleException(origin, false) }
            waitFor { app.blocker.status.startsWith("READY_CONFIG_ACK") }
            inst.runOnMainSync { app.coordinator.reload() }
            assertTrue(device.wait(Until.hasObject(By.text("AD_ALLOWED CONTROL_OK")), 30000))
            inst.runOnMainSync { assertTrue(app.coordinator.scheduler.liveCount <= 2) }
        } finally {
            waitFor { !app.blocker.busy }
            inst.runOnMainSync {
                if (app.blocker.isException(origin, false) != hadException) app.blocker.toggleException(origin, false)
                app.blocker.setUserRules(previousUser, false); app.blocker.setMode(previousMode)
            }
        }
        // CSS/フォームの実画面判定とserver /countsの照合はdocsの追加ゲート。
    }
}
