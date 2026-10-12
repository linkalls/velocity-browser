package dev.velocity.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.velocity.browser.ui.*
import org.junit.Assert.*
import org.junit.Test

/** Real Gecko fixtures; geometry/identity assertions are not physical-device FPS claims. */
class V04UiIntegrationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext.applicationContext as VelocityApp
    private val device get()=UiDevice.getInstance(inst)
    private fun main(action:()->Unit)=inst.onMainForTest(action)
    private fun chrome():BrowserChromeView {
        val field=MainActivity::class.java.getDeclaredField("chrome").apply {isAccessible=true}
        return field.get(app.coordinator.host!!.activity) as BrowserChromeView
    }
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){var ready=false;main {ready=condition()};if(ready)return;SystemClock.sleep(80)};fail(message)}
    private fun clickDescription(label:String){assertTrue(label,device.wait(Until.hasObject(By.desc(label)),15000));device.findObject(By.desc(label)).click();device.waitForIdle()}
    private fun closeSettings(){assertTrue(device.wait(Until.hasObject(By.text("閉じる")),10000));device.findObject(By.text("閉じる")).click();device.waitForIdle()}
    private fun input()=device.findObjects(By.clazz("android.widget.EditText")).firstOrNull {it.resourceName!="dev.velocity.browser:id/address"}
    private fun launch(){inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await("startup"){app.coordinator.host!=null&&!app.blocker.busy&&app.coordinator.scheduler.selectedId!=null};main {app.coordinator.scheduler.tabs.forEach {app.coordinator.close(it.id,true)}}}
    private fun cleanup(){main {app.appearance.dismissFor(app.coordinator.host!!.activity);app.coordinator.scheduler.tabs.forEach {app.coordinator.close(it.id,true)};app.appearance.select(ThemeController.Mode.SYSTEM)};device.setOrientationNatural();device.unfreezeRotation()}
    private class Fixture(private val dynamic:Boolean=false):AutoCloseable {
        private val server=java.net.ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"))
        private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
        val url="http://127.0.0.1:${server.localPort}/"
        init {io.execute {while(!server.isClosed)try {server.accept().use {socket->
            socket.soTimeout=5000;val reader=socket.getInputStream().bufferedReader();reader.readLine();while(!reader.readLine().isNullOrEmpty()){}
            val html=("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>V04_FIXTURE</title><style>body{margin:0;padding:16px 16px 64px;font:16px sans-serif}p{min-height:80px}footer{position:fixed;bottom:0;left:0;right:0;background:#e0e4e9;padding:12px}</style><label>V04_INPUT<input value=INITIAL_DRAFT aria-label=V04_INPUT></label>"+(1..100).joinToString(""){"<p>Fixture row $it</p>"}+"<footer>FIXED_FOOTER</footer>"+if(dynamic)"<script>const box=document.createElement('section');document.body.append(box);setInterval(()=>{const p=document.createElement('p');p.textContent='Dynamic fixture';box.append(p);while(box.children.length>24)box.firstElementChild.remove()},250)</script>" else "").toByteArray()
            socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray());write(html);flush()}
        }}catch(_:java.io.IOException){}}}
        override fun close(){server.close();io.shutdownNow()}
    }
    private fun page(f:Fixture):String {var id="";main {app.coordinator.createTab(f.url);id=app.coordinator.scheduler.selectedId!!};await("fixture"){app.coordinator.scheduler.tab(id)?.title=="V04_FIXTURE"};assertTrue(device.wait(Until.hasObject(By.text("V04_INPUT")),15000));return id}
    @Test fun compactMenuAndPlacementPreserveRealDraftAndSession() {
        launch();Fixture().use {f->try {
            val id=page(f)
            main {assertEquals("127.0.0.1",chrome().address.text.toString());chrome().address.performClick();assertEquals(f.url,chrome().address.text.toString());chrome().cancelAddressEdit()}
            device.waitForIdle()
            val before=app.getSharedPreferences("settings",0).getBoolean("address_bottom",true)
            assertNotNull(input());input()!!.text="V04_UNSAVED"
            var identity:Long?=null;var epoch=0L;var session:org.mozilla.geckoview.GeckoSession?=null
            main {val c=chrome();identity=app.coordinator.scheduler.tab(id)!!.identity;epoch=app.coordinator.scheduler.tab(id)!!.epoch;session=c.geckoView.session;assertNull(c.title.parent);assertNull(c.forward.parent);assertTrue(c.back.height>=inst.targetContext.dp(48))}
            clickDescription("メニュー");assertTrue("new tab menu route",device.wait(Until.hasObject(By.desc("新しいタブ")),10000));assertTrue("private tab menu route",device.wait(Until.hasObject(By.desc("新しいプライベートタブ")),10000));closeSettings()
            for(bottom in listOf(false,true)){main {chrome().setAddressBottom(bottom)};device.waitForIdle();main {assertSame(session,chrome().geckoView.session);assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity);assertEquals(epoch,app.coordinator.scheduler.tab(id)!!.epoch)};assertEquals("V04_UNSAVED",input()!!.text)}
            main {chrome().setAddressBottom(before)}
        }finally {cleanup()}}
    }
    @Test fun nativeHomeAndLargeFontKeepExplicitRoutesAndPrivateLabel() {
        val font=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
        launch();try {
            main {app.coordinator.createTab(privateMode=true)}
            assertTrue(device.wait(Until.hasObject(By.text("velocity")),10000));assertFalse(device.hasObject(By.text("Wikipedia  ↗")))
            assertTrue(device.hasObject(By.text("非公開")))
            assertTrue(device.hasObject(By.desc("ホームの検索・URL入力")))
            main {
                val field=BrowserChromeView::class.java.getDeclaredField("homeSearch").apply {isAccessible=true}
                val search=field.get(chrome()) as android.widget.EditText
                val background=search.backgroundTintList?.defaultColor ?: (search.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
                fun luma(color:Int):Double {
                    fun channel(n:Int):Double {val v=n/255.0;return if(v<=.04045)v/12.92 else Math.pow((v+.055)/1.055,2.4)}
                    return .2126*channel(android.graphics.Color.red(color))+.7152*channel(android.graphics.Color.green(color))+.0722*channel(android.graphics.Color.blue(color))
                }
                val a=luma(search.currentHintTextColor);val b=luma(background)
                assertTrue("actual native search hint contrast",(maxOf(a,b)+.05)/(minOf(a,b)+.05)>=4.5)
            }
            device.findObject(By.desc("ホームの検索・URL入力")).text="PRIVATE_NATIVE_DRAFT"
            main {repeat(32){app.appearance.track(android.app.Dialog(app.coordinator.host!!.activity))}}
            System.gc()
            device.executeShellCommand("settings put system font_scale 2.0")
            await("large-font activity"){app.coordinator.host!=null&&app.coordinator.host!!.activity.resources.configuration.fontScale>=1.9f}
            device.setOrientationLeft();device.waitForIdle()
            await("large-font compact toolbar"){chrome().back.height>=inst.targetContext.dp(48)&&chrome().address.width>=inst.targetContext.dp(48)}
            assertTrue(device.wait(Until.hasObject(By.text("PRIVATE_NATIVE_DRAFT")),10000))
            clickDescription("メニュー");clickDescription("設定")
            assertTrue(device.wait(Until.hasObject(By.desc("設定を検索")),10000));closeSettings()
        }finally {device.executeShellCommand("settings put system font_scale $font");cleanup()}
    }
    @Test fun actualStaticAndDynamicScrollUseToolbarSignalsWithoutSessionReplacement() {
        launch();val prefs=app.getSharedPreferences("settings",0);val oldBottom=prefs.getBoolean("address_bottom",true);val oldScroll=prefs.getBoolean("toolbar_scroll",true)
        try {for(dynamic in listOf(false,true)){Fixture(dynamic).use {f->
            val id=page(f);var identity:Long?=null;var epoch=0L;var session:org.mozilla.geckoview.GeckoSession?=null
            main {chrome().setAddressBottom(true);prefs.edit().putBoolean("toolbar_scroll",true).apply();identity=app.coordinator.scheduler.tab(id)!!.identity;epoch=app.coordinator.scheduler.tab(id)!!.epoch;session=chrome().geckoView.session;chrome().cancelAddressEdit()}
            device.waitForIdle()
            val w=device.displayWidth;val h=device.displayHeight
            device.swipe(w/2,h*3/4,w/2,h/3,40)
            await("Gecko toolbar hide on ${if(dynamic)"dynamic" else "static"} scroll") {chrome().toolbarVisibleFraction==0f}
            device.swipe(w/2,h/3,w/2,h*3/4,40)
            await("Gecko toolbar show on reverse scroll") {chrome().toolbarVisibleFraction==1f}
            main {assertSame(session,chrome().geckoView.session);assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity);assertEquals(epoch,app.coordinator.scheduler.tab(id)!!.epoch)}
            main {app.coordinator.close(id,true)}
        }} }finally {prefs.edit().putBoolean("address_bottom",oldBottom).putBoolean("toolbar_scroll",oldScroll).apply();cleanup()}
    }
    @Test fun clippingMatrixAndLayoutDuringMotionRetainSession() {
        launch();val prefs=app.getSharedPreferences("settings",0)
        val oldBottom=prefs.getBoolean("address_bottom",true);val oldScroll=prefs.getBoolean("toolbar_scroll",true);val oldMode=app.blocker.mode
        val oldAnimation=device.executeShellCommand("settings get global animator_duration_scale").trim().toFloatOrNull()?:1f
        Fixture().use {f->try {
            val id=page(f);var session:org.mozilla.geckoview.GeckoSession?=null
            main {session=chrome().geckoView.session;chrome().cancelAddressEdit()}
            device.waitForIdle()
            for(mode in listOf(dev.velocity.browser.block.BlockController.Mode.ETP_ONLY,dev.velocity.browser.block.BlockController.Mode.LITE)) {
            main {app.blocker.setMode(mode)}
            await("block mode $mode"){!app.blocker.busy&&app.blocker.mode==mode}
            for(scale in listOf(0,1)) {
            device.executeShellCommand("settings put global animator_duration_scale $scale")
            device.waitForIdle()
            for(bottom in listOf(true,false))for(dynamic in listOf(false,true)) {
                main {chrome().setAddressBottom(bottom);prefs.edit().putBoolean("toolbar_scroll",dynamic).apply()}
                device.waitForIdle()
                val w=device.displayWidth;val h=device.displayHeight
                device.swipe(w/2,h*3/4,w/2,h/3,35)
                await("final toolbar geometry") {chrome().toolbarVisibleFraction==if(dynamic)0f else 1f}
                main {
                    val height=DesignTokens.toolbarHeight(inst.targetContext)
                    assertEquals(if(dynamic)-height else height,chrome().toolbarClipping)
                    assertSame(session,chrome().geckoView.session)
                }
                // Control a hiding animation after pinning the bar shown. A fling
                // may still legitimately request hide; it must not race a "show"
                // assertion intended to test layout position preservation.
                main {chrome().setModalBlocked(true)}
                device.waitForIdle()
                main {
                    chrome().setModalBlocked(false)
                    if(dynamic&&scale==1){
                        chrome().onEngineToolbar(false,ToolbarMotion.Source.UI)
                        val field=BrowserChromeView::class.java.getDeclaredField("animator").apply {isAccessible=true}
                        val animator=field.get(chrome()) as android.animation.ValueAnimator
                        animator.pause();animator.setCurrentFraction(.35f)
                        val before=chrome().toolbarVisibleFraction
                        val c=chrome();c.layout(c.left,c.top,c.right,c.bottom-1)
                        assertEquals("layout preserves paused intermediate position",before,c.toolbarVisibleFraction,0.0001f)
                        c.layout(c.left,c.top,c.right,c.bottom+1);animator.resume()
                    }
                }
                // A UI pin owns the final shown state independently of remaining
                // inertia. Keep the session and verify the static baseline again.
                main {chrome().setModalBlocked(true)}
                await("shown after layout interruption"){chrome().toolbarVisibleFraction==1f}
                main {chrome().setModalBlocked(false)}
                main {assertSame(session,chrome().geckoView.session);assertEquals(id,app.coordinator.scheduler.selectedId)}
            }
            }
            }
        }finally {device.executeShellCommand("settings put global animator_duration_scale $oldAnimation");await("block settles before restore"){!app.blocker.busy};main {app.blocker.setMode(oldMode);chrome().setAddressBottom(oldBottom);prefs.edit().putBoolean("toolbar_scroll",oldScroll).apply()};cleanup()}}
    }

    @Test fun homeShortcutGridOrderingAndMenuRoutesRetainIdentity() {
        launch();val prefs=app.getSharedPreferences("settings",0);val previous=prefs.getString("home_shortcuts",null)
        try {
            main {app.coordinator.createTab();prefs.edit().putString("home_shortcuts","[{\"title\":\"Alpha\",\"url\":\"https://example.com/a\"},{\"title\":\"Bravo\",\"url\":\"https://example.com/b\"}]").apply()}
            assertTrue(device.wait(Until.hasObject(By.desc("ショートカット · Alpha")),10000))
            val target=device.findObject(By.desc("ショートカット · Alpha"));assertTrue(target.visibleBounds.height()>=inst.targetContext.dp(48))
            target.longClick();assertTrue(device.wait(Until.hasObject(By.text("Alpha")),10000));device.findObject(By.text("Alpha")).click()
            assertTrue(device.wait(Until.hasObject(By.text("後へ移動")),10000));device.findObject(By.text("後へ移動")).click();device.waitForIdle()
            main {assertEquals("Bravo",org.json.JSONArray(prefs.getString("home_shortcuts","[]")).getJSONObject(0).getString("title"))}
            var id="";var identity:Long?=null
            main {id=app.coordinator.scheduler.selectedId!!;identity=app.coordinator.scheduler.tab(id)!!.identity}
            clickDescription("メニュー");assertTrue(device.wait(Until.hasObject(By.desc("共有")),10000));assertFalse(device.findObject(By.desc("共有")).isEnabled)
            clickDescription("設定");assertTrue(device.wait(Until.hasObject(By.desc("設定を検索")),10000));closeSettings()
            main {assertEquals(id,app.coordinator.scheduler.selectedId);assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity)}
        }finally {main {if(previous==null)prefs.edit().remove("home_shortcuts").apply() else prefs.edit().putString("home_shortcuts",previous).apply()};cleanup()}
    }
    @Test fun captureMajorScreensInBothThemes() {
        launch()
        val label=InstrumentationRegistry.getArguments().getString("screenshot_label","after")
        require(label=="before"||label=="after")
        val folder=java.io.File(inst.targetContext.getExternalFilesDir(null),"screenshots/$label").apply {mkdirs()}
        val export="/sdcard/Download/Velocity-validation/$label"
        device.executeShellCommand("mkdir -p $export")
        fun capture(name:String){
            device.waitForIdle();val file=java.io.File(folder,"$name.png")
            assertTrue("capture $name",device.takeScreenshot(file))
            // Gradle uninstalls the target after connected tests: export fixture-only
            // screenshots through UiAutomation's shell identity before that cleanup.
            device.executeShellCommand("cp ${file.absolutePath} $export/$name.png")
        }
        Fixture().use {f->try {
            for(mode in listOf(ThemeController.Mode.LIGHT,ThemeController.Mode.DARK)) {
                val prefix=mode.name.lowercase()
                main {app.coordinator.createTab();app.appearance.select(mode)}
                capture("$prefix-home")
                val id=page(f);capture("$prefix-browser")
                main {chrome().onTabs()};assertTrue(device.wait(Until.hasObject(By.text("完了")),10000));capture("$prefix-tabs");device.findObject(By.text("完了")).click()
                clickDescription("メニュー");assertTrue(device.wait(Until.hasObject(By.text("閉じる")),10000));capture("$prefix-menu");closeSettings()
                main {chrome().onSettings()};assertTrue(device.wait(Until.hasObject(By.desc("設定を検索")),10000));capture("$prefix-settings");closeSettings()
                main {app.coordinator.close(id,true)}
            }
        }finally {cleanup()}}
    }

}
