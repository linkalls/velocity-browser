package dev.velocity.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velocity.browser.tabs.Event
import dev.velocity.browser.ui.ThemeController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local HTTP fixtures, real Gecko inputs, native dialogs; no third-party website dependencies. */
@RunWith(AndroidJUnit4::class)
class BrowserUiIntegrationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as VelocityApp
    private val device get() = UiDevice.getInstance(inst)
    private fun main(action: () -> Unit) = inst.onMainForTest(action)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false; main { ready = condition() }; if (ready) return
            SystemClock.sleep(100)
        }; fail(message)
    }
    private fun launch() {
        inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser","address")),15000))
        await("initialization") { app.coordinator.scheduler.selectedId != null && !app.blocker.busy && app.blocker.status != "NOT_INITIALIZED" }
        main {
            app.coordinator.scheduler.pendingId?.let(app.coordinator::cancelPending)
            app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) }
        }
    }
    private fun click(text: String) { assertTrue("missing $text",device.wait(Until.hasObject(By.text(text)),10000)); device.findObject(By.text(text)).click(); device.waitForIdle() }
    private fun clickDescription(description: String) { assertTrue("missing $description",device.wait(Until.hasObject(By.desc(description)),10000)); device.findObject(By.desc(description)).click();device.waitForIdle() }
    private fun field() = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { it.resourceName != "dev.velocity.browser:id/address" }
    private fun awaitField() {
        val deadline = SystemClock.elapsedRealtime()+15000
        while (field() == null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertNotNull("Gecko form input",field())
    }
    private class Fixture : AutoCloseable {
        private val server = java.net.ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"))
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${server.localPort}/"
        init { io.execute {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout=5000; val reader=socket.getInputStream().bufferedReader()
                if (reader.readLine().isNullOrEmpty()) return@use
                while (!reader.readLine().isNullOrEmpty()) { }
                val body="""<!doctype html><meta charset=utf-8><title>UI_FIXTURE</title><label>UI_DRAFT<input aria-label=UI_DRAFT></label><p id=scheme></p><script>const q=matchMedia('(prefers-color-scheme: dark)');function update(){document.querySelector('#scheme').textContent=q.matches?'SCHEME_DARK':'SCHEME_LIGHT'}q.addEventListener('change',update);update()</script>""".toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
            } } catch (_:java.io.IOException) { }
        } }
        override fun close() { server.close();io.shutdownNow() }
    }
    private fun form(f: Fixture): String {
        var id=""; main { app.coordinator.createTab(f.url); id=app.coordinator.scheduler.selectedId!! }
        await("fixture document title") { app.coordinator.scheduler.selectedId == id && app.coordinator.scheduler.tab(id)?.title == "UI_FIXTURE" }
        device.waitForIdle(); awaitField(); return id
    }
    private fun cleanup() {
        main {
            app.coordinator.scheduler.pendingId?.let(app.coordinator::cancelPending)
            app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) }
            app.appearance.select(ThemeController.Mode.SYSTEM)
        }; device.setOrientationNatural();device.unfreezeRotation()
    }
    @Test fun closeCancelRetainsSheetDraftAndAccessible48dpTarget() {
        launch(); Fixture().use { f -> try {
            val id=form(f); main { app.coordinator.scheduler.tabs.filter { it.id!=id }.forEach { app.coordinator.close(it.id,true) } }
            field()!!.text="KEEP_ON_CANCEL"
            var identity:Long?=null; main { identity=app.coordinator.scheduler.tab(id)!!.identity }
            device.findObject(By.descStartsWith("タブ ")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("『UI_FIXTURE』を閉じる")),10000))
            val close=device.findObject(By.desc("『UI_FIXTURE』を閉じる")); val bounds=close.visibleBounds
            val minimum=(48*inst.targetContext.resources.displayMetrics.density).toInt()
            assertTrue(bounds.width()>=minimum);assertTrue(bounds.height()>=minimum)
            close.click(); click("キャンセル")
            main {
                val sheetField = MainActivity::class.java.getDeclaredField("tabSheet").apply { isAccessible=true }
                val sheet = sheetField.get(app.coordinator.host!!.activity) as dev.velocity.browser.ui.TabSwitcherView
                assertTrue("cancel must retain the native tab dialog",sheet.isShowing)
            }
            assertTrue("tab sheet accessibility after warning dismiss",device.wait(Until.hasObject(By.desc("『UI_FIXTURE』を閉じる")),10000))
            main { assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity) }
            click("完了"); awaitField();assertEquals("KEEP_ON_CANCEL",field()!!.text)
            device.findObject(By.descStartsWith("タブ ")).click()
            clickDescription("『UI_FIXTURE』を閉じる");click("このタブを閉じる")
            // Late page-risk updates can invalidate a confirmation. Reconfirm from
            // the still-open sheet; never bypass the stale-confirmation guard.
            var retained = false
            main { retained = app.coordinator.scheduler.tab(id) != null }
            if (retained) {
                main { assertEquals(identity, app.coordinator.scheduler.tab(id)!!.identity) }
                clickDescription("『UI_FIXTURE』を閉じる");click("このタブを閉じる")
            }
            main {
                assertNull(app.coordinator.scheduler.tab(id));assertEquals(1,app.coordinator.scheduler.tabs.size)
                assertEquals("about:blank",app.coordinator.scheduler.tabs.single().url)
                assertNotNull(app.coordinator.scheduler.selectedId);assertEquals(1,app.coordinator.scheduler.liveCount)
            };click("完了")
        } finally { cleanup() } }
    }
    @Test fun protectedThirdSeventhAndTwentiethTabsKeepDraftAfterRotation() {
        launch();Fixture().use { f -> try {
            val first=form(f);field()!!.text="FIRST_DRAFT"
            main { app.coordinator.scheduler.tabs.filter { it.id!=first }.forEach { app.coordinator.close(it.id,true) } }
            val second=form(f);field()!!.text="SECOND_DRAFT"
            var firstIdentity:Long?=null; var secondIdentity:Long?=null
            main {
                firstIdentity=app.coordinator.scheduler.tab(first)!!.identity
                secondIdentity=app.coordinator.scheduler.tab(second)!!.identity
                repeat(18) { app.coordinator.createTab(f.url) }
                assertEquals(20,app.coordinator.scheduler.liveCount);assertNull(app.coordinator.scheduler.pendingId)
                assertEquals(firstIdentity,app.coordinator.scheduler.tab(first)!!.identity)
                assertEquals(secondIdentity,app.coordinator.scheduler.tab(second)!!.identity)
                app.coordinator.memoryPressure();assertEquals(20,app.coordinator.scheduler.liveCount)
            }
            val old=app.coordinator.host;device.setOrientationLeft();device.waitForIdle()
            await("fresh activity after rotation") { app.coordinator.host!==old && app.coordinator.host!=null }
            main { app.coordinator.select(first) };awaitField();assertEquals("FIRST_DRAFT",field()!!.text)
            main { app.coordinator.select(second) };awaitField();assertEquals("SECOND_DRAFT",field()!!.text)
        } finally { cleanup() } }
    }
    @Test fun appearancePersistsAndUpdatesWebSchemeWithoutReloadOrDraftLoss() {
        val nightBefore = inst.targetContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        launch();Fixture().use { f -> try {
            main { app.appearance.select(ThemeController.Mode.LIGHT) }
            val id=form(f);field()!!.text="THEME_DRAFT"
            assertTrue(device.wait(Until.hasObject(By.text("SCHEME_LIGHT")),15000))
            var identity:Long?=null;var epoch=0L
            main { val t=app.coordinator.scheduler.tab(id)!!;identity=t.identity;epoch=t.epoch;app.appearance.select(ThemeController.Mode.DARK) }
            assertTrue(device.wait(Until.hasObject(By.text("SCHEME_DARK")),15000))
            assertEquals("THEME_DRAFT",field()!!.text)
            main { assertTrue(app.appearance.palette().dark);assertEquals(ThemeController.Mode.DARK,ThemeController(app).mode);assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity);assertEquals(epoch,app.coordinator.scheduler.tab(id)!!.epoch);app.coordinator.host!!.activity.recreate() }
            await("recreated activity") { app.coordinator.host!=null }
            assertTrue(device.wait(Until.hasObject(By.text("SCHEME_DARK")),15000));awaitField();assertEquals("THEME_DRAFT",field()!!.text)
            main { app.appearance.select(ThemeController.Mode.LIGHT) }
            assertTrue(device.wait(Until.hasObject(By.text("SCHEME_LIGHT")),15000));assertEquals("THEME_DRAFT",field()!!.text)
            device.executeShellCommand("cmd uimode night yes")
            device.waitForIdle()
            main { assertFalse("explicit light ignores OS night",app.appearance.dark());app.appearance.select(ThemeController.Mode.SYSTEM) }
            assertTrue(device.wait(Until.hasObject(By.text("SCHEME_DARK")),15000))
            device.executeShellCommand("cmd uimode night no")
            assertTrue(device.wait(Until.hasObject(By.text("SCHEME_LIGHT")),15000))
            assertEquals("THEME_DRAFT",field()!!.text)
            main { assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity);assertEquals(epoch,app.coordinator.scheduler.tab(id)!!.epoch) }
        } finally { device.executeShellCommand("cmd uimode night " + if (nightBefore) "yes" else "no");cleanup() } }
    }
    @Test fun privateCloseWarningAndNativePaletteHaveSafeContrast() {
        launch();try {
            main {
                app.coordinator.createTab(privateMode=true)
                assertTrue(app.coordinator.host!!.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                val id=app.coordinator.scheduler.selectedId!!;app.coordinator.close(id)
                assertNotNull(app.coordinator.scheduler.tab(id))
                for (mode in listOf(ThemeController.Mode.LIGHT,ThemeController.Mode.DARK)) {
                    app.appearance.select(mode);val p=app.appearance.palette()
                    fun luminance(color:Int):Double { fun channel(n:Int):Double { val v=n/255.0;return if(v<=.04045)v/12.92 else Math.pow((v+.055)/1.055,2.4) };return .2126*channel(android.graphics.Color.red(color))+.7152*channel(android.graphics.Color.green(color))+.0722*channel(android.graphics.Color.blue(color)) }
                    fun ratio(a:Int,b:Int):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
                    for (surface in listOf(p.surface,p.card,p.background)) {assertTrue(ratio(p.text,surface)>=4.5);assertTrue(ratio(p.muted,surface)>=4.5);assertTrue(ratio(p.danger,surface)>=4.5);assertTrue(ratio(p.accent,surface)>=3.0)}
                }
            };click("キャンセル")
        } finally { cleanup() }
    }    @Test fun hundredLogicalTabsSearchPinCloseUndoWithoutPreloading() {
        launch();try {
            main {repeat(100){app.coordinator.dispatch(Event.CreateTab("logical-$it","https://example.com/$it",title="Logical$it"))};assertEquals(1,app.coordinator.scheduler.liveCount)}
            device.findObject(By.descStartsWith("タブ ")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("タブを検索")),10000))
            val query=device.findObject(By.desc("タブを検索"));assertNotNull(query);query.text="Logical99"
            assertTrue(device.wait(Until.hasObject(By.desc("『Logical99』を閉じる")),10000))
            clickDescription("『Logical99』の操作");click("固定");main {assertTrue(app.coordinator.scheduler.tab("logical-99")!!.pinned)}
            clickDescription("『Logical99』を閉じる");main {assertNull(app.coordinator.scheduler.tab("logical-99"));assertTrue(app.coordinator.canUndo())}
            click("閉じるを取り消す（ページを再読み込み）")
            main {assertTrue(app.coordinator.scheduler.tabs.any {it.url=="https://example.com/99"&&it.pinned});assertFalse(app.coordinator.canUndo())}
            click("完了")
        } finally {cleanup()}
    }

    @Test fun settingsSearchSwitchAndBackPreserveSelectedTab() {
        launch()
        val preferences = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val before = preferences.getBoolean("address_swipe", true)
        var id: String? = null
        main { id = app.coordinator.scheduler.selectedId }
        try {
            clickDescription("メニュー");clickDescription("設定")
            assertTrue(device.wait(Until.hasObject(By.desc("設定を検索")), 10000))
            device.findObject(By.desc("設定を検索")).text = "アドレスバーのスワイプ"
            val label = "アドレスバーのスワイプ、" + if (before) "オン" else "オフ"
            clickDescription(label)
            assertTrue(device.wait(Until.hasObject(By.desc("アドレスバーのスワイプ、" + if (before) "オフ" else "オン")), 10000))
            assertEquals(!before, preferences.getBoolean("address_swipe", true))
            device.findObject(By.desc("設定を検索")).text = "NO_MATCH_SETTINGS"
            assertTrue(device.wait(Until.hasObject(By.text("一致する設定がありません")), 10000))
            assertFalse(device.hasObject(By.descStartsWith("アドレスバーのスワイプ、")))
            click("閉じる")
            main { assertEquals(id, app.coordinator.scheduler.selectedId) }
        } finally {
            preferences.edit().putBoolean("address_swipe", before).commit()
            cleanup()
        }
    }

}
