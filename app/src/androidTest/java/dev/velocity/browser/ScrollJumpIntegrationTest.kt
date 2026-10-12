package dev.velocity.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.velocity.browser.block.BlockController
import dev.velocity.browser.diagnostics.ScrollDiagnostics
import dev.velocity.browser.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local real-Gecko evidence, not physical pan/optical-jump acceptance. */
class ScrollJumpIntegrationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext.applicationContext as VelocityApp
    private val device get()=UiDevice.getInstance(inst)
    private fun main(action:()->Unit)=inst.onMainForTest(action)
    private fun chrome():BrowserChromeView {val f=MainActivity::class.java.getDeclaredField("chrome").apply {isAccessible=true};return f.get(app.coordinator.host!!.activity) as BrowserChromeView}
    private fun await(label:String,test:()->Boolean){val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){var ok=false;main {ok=test()};if(ok)return;SystemClock.sleep(80)};fail(label)}
    private fun launch(){inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await("init"){app.coordinator.host!=null&&!app.blocker.busy};main {app.appearance.dismissFor(app.coordinator.host!!.activity);app.coordinator.scheduler.tabs.forEach {app.coordinator.close(it.id,true)}}}
    private fun cleanup(){main {ScrollDiagnostics.enable(false);app.appearance.dismissFor(app.coordinator.host!!.activity);app.coordinator.scheduler.tabs.forEach {app.coordinator.close(it.id,true)}}}
    private class Fixture(private val html:ByteArray):AutoCloseable {
        private val server=java.net.ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"))
        private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
        val url="http://127.0.0.1:${server.localPort}/"
        init {io.execute {while(!server.isClosed)try{server.accept().use {s->s.soTimeout=5000;val input=s.getInputStream().bufferedReader();input.readLine();while(!input.readLine().isNullOrEmpty()){};s.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray());write(html);flush()}}}catch(_:java.io.IOException){}}}
        override fun close(){server.close();io.shutdownNow()}
    }
    private fun fixture()=Fixture(inst.context.assets.open("scroll-jump.html").use {it.readBytes()})
    private fun page(f:Fixture):String {var id="";main {app.coordinator.createTab(f.url);id=app.coordinator.scheduler.selectedId!!;ScrollDiagnostics.enable(true);app.coordinator.render()};await("fixture ready"){app.coordinator.scheduler.tab(id)?.title=="JUMP_READY"};assertTrue(device.wait(Until.hasObject(By.text("FOOTER_TAP")),15000));device.waitForIdle();return id}
    private fun export(label:String):String {
        var csv="";main {csv=ScrollDiagnostics.snapshot()}
        val file=File(inst.targetContext.getExternalFilesDir(null),"scroll-jump-$label.csv");file.writeText(csv)
        device.executeShellCommand("mkdir -p /sdcard/Download/Velocity-scroll-jump")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/Velocity-scroll-jump/$label.csv")
        return csv
    }
    @Test fun diagnosticOptInCapacityAndPrivateExclusion() {
        launch();try {
            main {
                assertFalse(ScrollDiagnostics.enabled);ScrollDiagnostics.record(ScrollDiagnostics.Event.SAMPLE,y=100f);assertEquals("",ScrollDiagnostics.snapshot())
                ScrollDiagnostics.enable(true);ScrollDiagnostics.context("raw-normal-id",21,2,false)
                repeat(2000){ScrollDiagnostics.record(ScrollDiagnostics.Event.SAMPLE,y=it.toFloat())};assertEquals(768,ScrollDiagnostics.size)
                assertFalse(ScrollDiagnostics.snapshot().contains("raw-normal-id"))
                ScrollDiagnostics.context("private-tab-secret",42,3,true);repeat(100){ScrollDiagnostics.record(ScrollDiagnostics.Event.SAMPLE,y=987654f)}
                assertEquals(0,ScrollDiagnostics.size);assertFalse(ScrollDiagnostics.snapshot().contains("987654"));assertFalse(ScrollDiagnostics.snapshot().contains("private-tab-secret"))
                ScrollDiagnostics.enable(false);assertEquals("",ScrollDiagnostics.snapshot())
            }
        }finally {cleanup()}
    }
    @Test fun realStaticDynamicMatrixKeepsFooterTapAndSessionIdentity() {
        launch();val prefs=app.getSharedPreferences("settings",0)
        val oldScroll=prefs.getBoolean("toolbar_scroll",true);val oldBottom=prefs.getBoolean("address_bottom",true)
        val oldMode=app.blocker.mode;val oldCollapse=app.blocker.collapseEnabled;val oldRules=app.blocker.userRules(false)
        fixture().use {f->try {
            main {app.blocker.setUserRules(oldRules+"\n127.0.0.1##.ad-slot",false)}
            var scenario=0
            for(mode in listOf(BlockController.Mode.ALL_OFF,BlockController.Mode.ETP_ONLY,BlockController.Mode.LITE)) {
                await("block idle"){!app.blocker.busy};main {app.blocker.setMode(mode)};await("mode"){!app.blocker.busy&&app.blocker.mode==mode}
                for(collapse in if(mode==BlockController.Mode.LITE)listOf(false,true) else listOf(false)) {
                    main {app.blocker.setCollapse(collapse)}
                    for(bottom in listOf(true,false))for(dynamic in listOf(false,true)) {
                        main {chrome().setAddressBottom(bottom);prefs.edit().putBoolean("toolbar_scroll",dynamic).apply()}
                        val id=page(f);var identity:Long?=null;var epoch=0L;var session:org.mozilla.geckoview.GeckoSession?=null
                        main {val t=app.coordinator.scheduler.tab(id)!!;identity=t.identity;epoch=t.epoch;session=chrome().geckoView.session}
                        val w=device.displayWidth;val h=device.displayHeight
                        device.swipe(w/2,h*3/4,w/2,h/3,70);device.waitForIdle()
                        device.swipe(w/2,h/3,w/2,h*3/4,15);device.waitForIdle()
                        // Footer's real click is the coordinate test; no JS scroll compensation.
                        assertTrue(device.wait(Until.hasObject(By.text("FOOTER_TAP")),10000));device.findObject(By.text("FOOTER_TAP")).click()
                        await("fixed footer accepts real tap"){app.coordinator.scheduler.tab(id)?.title=="JUMP_TAP_OK"}
                        assertTrue(device.wait(Until.hasObject(By.text("INSERT")),10000));device.findObject(By.text("INSERT")).click()
                        await("dynamic insertion"){app.coordinator.scheduler.tab(id)?.title=="JUMP_INSERTED"}
                        device.swipe(w/2,h*3/4,w/2,h/3,25);device.waitForIdle()
                        main {assertSame(session,chrome().geckoView.session);assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity);assertEquals(epoch,app.coordinator.scheduler.tab(id)!!.epoch)}
                        val csv=export("matrix-${scenario++}");assertTrue(csv.contains(",1,"));assertFalse(csv.contains(f.url))
                        main {app.coordinator.close(id,true)}
                    }
                }
            }
            assertEquals(16,scenario)
        }finally {await("restore idle"){!app.blocker.busy};main {app.blocker.setUserRules(oldRules,false);app.blocker.setCollapse(oldCollapse);app.blocker.setMode(oldMode);prefs.edit().putBoolean("toolbar_scroll",oldScroll).apply();chrome().setAddressBottom(oldBottom)};cleanup()}}
    }
    @Test fun intentionalAnchorNestedAndFocusAreNotBlockedAndFormSurvives() {
        launch();fixture().use {f->try {
            val id=page(f);var identity:Long?=null;var epoch=0L
            main {identity=app.coordinator.scheduler.tab(id)!!.identity;epoch=app.coordinator.scheduler.tab(id)!!.epoch}
            device.findObject(By.text("ANCHOR")).click();await("intentional anchor allowed"){app.coordinator.scheduler.tab(id)?.title=="JUMP_ANCHOR"}
            device.swipe(device.displayWidth/2,device.displayHeight/3,device.displayWidth/2,device.displayHeight*3/4,40);device.waitForIdle()
            val csv=export("anchor-user");val samples=csv.lines().drop(1).map {it.split(',')}.filter {it.size==22&&it[1]==ScrollDiagnostics.Event.SAMPLE.ordinal.toString()}
            assertTrue("programmatic movement is separately recorded",samples.any {it[5]=="1"})
            assertTrue("real user pan recorded",samples.any {it[5]=="0"})
            device.findObject(By.text("NESTED")).click();await("nested area"){app.coordinator.scheduler.tab(id)?.title=="JUMP_NESTED_READY"}
            assertTrue(device.wait(Until.hasObject(By.text("Nested row 0")),10000))
            val nested=device.findObject(By.text("Nested row 0")).visibleBounds
            device.swipe(nested.centerX(),nested.bottom-6,nested.centerX(),nested.top+6,40)
            await("internal container moved"){app.coordinator.scheduler.tab(id)?.title?.let {it.startsWith("JUMP_NESTED_")&&it!="JUMP_NESTED_READY"}==true}
            export("nested-scroll")
            // Focus scrolls to the actual input; closing IME must preserve typed form content.
            device.findObject(By.text("FOCUS")).click();await("focus jump allowed"){app.coordinator.scheduler.tab(id)?.title=="JUMP_FOCUS"}
            assertTrue(device.wait(Until.hasObject(By.desc("JUMP_INPUT")),10000));device.findObject(By.desc("JUMP_INPUT")).text="KEEP_AFTER_ANCHOR"
            device.pressBack();main {chrome().onMenu()};assertTrue(device.wait(Until.hasObject(By.text("閉じる")),10000));device.findObject(By.text("閉じる")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("JUMP_INPUT")),10000));assertEquals("KEEP_AFTER_ANCHOR",device.findObject(By.desc("JUMP_INPUT")).text)
            main {assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity);assertEquals(epoch,app.coordinator.scheduler.tab(id)!!.epoch)}
            export("focus-ime-form")
        }finally {cleanup()}}
    }
    @Test fun realPinchZoomAndFollowingPanProduceSeparateDiagnosticSamples() {
        launch();fixture().use {f->try {
            val id=page(f)
            val w=device.displayWidth;val h=device.displayHeight
            device.swipe(w/2,h*3/4,w/2,h/3,50);device.waitForIdle()
            val down=SystemClock.uptimeMillis()
            val properties=Array(2){i->android.view.MotionEvent.PointerProperties().apply {this.id=i;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(2){android.view.MotionEvent.PointerCoords().apply {y=h/2f;pressure=1f;size=1f}}
            fun inject(action:Int,count:Int,distance:Float){
                coords[0].x=w/2f-distance;coords[1].x=w/2f+distance
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                assertTrue(inst.uiAutomation.injectInputEvent(event,true));event.recycle()
            }
            inject(android.view.MotionEvent.ACTION_DOWN,1,24f)
            inject(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,24f)
            for(i in 1..14){inject(android.view.MotionEvent.ACTION_MOVE,2,24f+i*(w*.3f-24f)/14);SystemClock.sleep(16)}
            inject(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,w*.3f)
            inject(android.view.MotionEvent.ACTION_UP,1,w*.3f)
            device.waitForIdle();device.swipe(w/2,h*3/4,w/2,h/3,60);device.waitForIdle()
            val csv=export("zoom-user")
            val zooms=csv.lines().drop(1).map {it.split(',')}.filter {it.size==22&&it[1]==ScrollDiagnostics.Event.SAMPLE.ordinal.toString()}.mapNotNull {it[12].toFloatOrNull()}.filter {it.isFinite()}
            assertTrue("real Gecko zoom changed",zooms.maxOrNull()!!>zooms.minOrNull()!!+.01f)
            main {assertEquals(id,app.coordinator.scheduler.selectedId)}
        }finally {cleanup()}}
    }

}
