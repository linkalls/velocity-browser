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
    @RequiresExternalFixture
    @Test fun liteHttpsBlockingAndExceptionPreserveTwoLiveLimit() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("blockFixtureOrigin")
        assumeTrue("HTTPS fixture absent: UNVERIFIED", origin != null)
        require(origin!!.matches(Regex("https://site\\.velocity\\.test(?::[0-9]+)?")))
        assertFixture(origin)
    }
    private fun assertFixture(origin: String) {
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
            app.blocker.setUserRules(if (origin.startsWith("http:")) "||127.0.0.2^\n127.0.0.1##.ad-slot" else "||ads.velocity.test^\nsite.velocity.test##.ad-slot", false)
            app.blocker.setMode(BlockController.Mode.ETP_ONLY)
        }
        try {
            waitFor { !app.blocker.busy && app.blocker.status.startsWith("DISABLED") }
            inst.runOnMainSync { app.coordinator.createTab("$origin/") }
            // 必ず先に同じ広告originのTLS/DNS/HTTP到達成功を証明。
            assertTrue("OFF baseline: ${device.findObject(By.textStartsWith("AD_"))?.text}; blocker=${app.blocker.status}", device.wait(Until.hasObject(By.text("AD_ALLOWED CONTROL_OK")), 30000))
            inst.runOnMainSync { app.blocker.setMode(BlockController.Mode.LITE) }
            waitFor { app.blocker.status.startsWith("READY_CONFIG_ACK") }
            inst.runOnMainSync { app.coordinator.reload() }
            assertTrue(device.wait(Until.hasObject(By.text("AD_FAILED CONTROL_OK")), 30000))
            waitFor { app.blocker.normalDecisions > 0 }
            assertTrue(device.wait(Until.hasObject(By.textContains("FORM_MUST_REMAIN")), 15000))
            assertFalse("standalone ad slot must be hidden", device.hasObject(By.text("CSS広告枠")))
            val field = device.findObjects(By.clazz("android.widget.EditText")).first { it.resourceName != "dev.velocity.browser:id/address" }
            field.text = "LITE_DRAFT_PRESERVED"
            inst.runOnMainSync { assertTrue(app.coordinator.scheduler.liveCount <= 2); app.blocker.toggleException(origin, false) }
            waitFor { app.blocker.status.startsWith("READY_CONFIG_ACK") }
            assertEquals("mode/config change must not reload draft", "LITE_DRAFT_PRESERVED", field.text)
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
        // server /counts照合・private API・4条件性能は別ゲート。
    }

    /** 実GeckoのwebRequest blocking / CSS / Native portを、外部DNSやCAなしで検証。 */
    @Test fun liteLoopbackBlockingCssAndDraftPreservation() {
        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("0.0.0.0"))
        val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        val origin = "http://127.0.0.1:${server.localPort}"
        val body = """<!doctype html><meta charset=utf-8><title>Lite fixture</title><p id=result>RUNNING</p><form><label>保持する入力<input aria-label=保持する入力></label></form><div class=ad-slot>CSS広告枠</div><div class=ad-slot><form><label>FORM_MUST_REMAIN<input></label></form></div><script>(async()=>{let ad=false,control=false;try{ad=(await fetch('http://127.0.0.2:${server.localPort}/ad')).ok}catch{}try{control=(await fetch('/control')).ok}catch{}document.querySelector('#result').textContent='AD_'+(ad?'ALLOWED':'FAILED')+' CONTROL_'+(control?'OK':'FAILED')})()</script>""".toByteArray()
        io.execute {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    // Gecko may preconnect and close without sending an HTTP request.
                    // A timeout/empty connection must not terminate the fixture accept loop.
                    try {
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val payload = if (path == "/") body else "OK".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${if (path == "/") "text/html; charset=utf-8" else "text/plain"}\r\nAccess-Control-Allow-Origin: $origin\r\nCache-Control: no-store\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(payload); flush()
                    }
                    } catch (_: java.io.IOException) { }
                }
            } catch (_: java.io.IOException) { }
        }
        try { assertFixture(origin) } finally { server.close(); io.shutdownNow() }
    }
}
