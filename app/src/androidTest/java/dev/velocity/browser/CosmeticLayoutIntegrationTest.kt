package dev.velocity.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.velocity.browser.block.BlockController
import org.junit.Assert.*
import org.junit.Test

/** Real Gecko layout, reversible CSS, protected controls and bounded dynamic insertions. */
class CosmeticLayoutIntegrationTest {
    @Test fun dedicated250pxGapCollapsesAndRestoresWithoutTouchingDraft() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext.applicationContext as VelocityApp
        val device=UiDevice.getInstance(inst);fun main(action:()->Unit)=inst.runOnMainSync(action)
        fun await(label:String,condition:()->Boolean){val limit=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<limit){var ok=false;main {ok=condition()};if(ok)return;SystemClock.sleep(100)};fail(label)}
        val server=java.net.ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"));val io=java.util.concurrent.Executors.newSingleThreadExecutor()
        val origin="http://127.0.0.1:${server.localPort}";var previousMode=BlockController.Mode.ETP_ONLY;var previousCosmetic=true;var previousCollapse=true
        val page="""<!doctype html><meta charset=utf-8><title>LAYOUT_WAIT</title><style>body{margin:0}.ad-wrapper{height:250px}.ad-slot{height:30px}</style>
            <div class=ad-wrapper id=gap><div class=ad-slot>advertisement fixture</div></div><p id=body>BODY_REMAINS</p>
            <div id=picker_fixture>PICKER_FIXTURE</div><header class=banner>HEADER_REMAINS</header><main>MAIN_REMAINS</main>
            <div class=ad-slot id=protected><form><input value=KEEP_DRAFT><button>FORM_REMAINS</button></form></div>
            <div class=ad-wrapper id=video><iframe src=about:blank title=VIDEO_REMAINS></iframe></div>
            <script>
            const gap=document.querySelector('#gap'),body=document.querySelector('#body'),form=document.querySelector('#protected'),video=document.querySelector('#video');
            const original=body.getBoundingClientRect().top;let cycles=0;
            const additions=setInterval(()=>{const wrapper=document.createElement('div');wrapper.className='ad-wrapper';wrapper.innerHTML='<div class=ad-slot>dynamic fixture</div>';document.body.appendChild(wrapper);setTimeout(()=>wrapper.remove(),170);if(++cycles===100)clearInterval(additions)},20);
            setInterval(()=>{
                const protectedOK=form.getBoundingClientRect().height>0&&form.querySelector('input').value==='KEEP_DRAFT'&&video.getBoundingClientRect().height>0;
                const gapHeight=gap.getBoundingClientRect().height;
                document.title=(!protectedOK?'LAYOUT_DATA_LOSS':gapHeight===0&&body.getBoundingClientRect().top<100?'LAYOUT_COLLAPSED':gapHeight===250?'LAYOUT_RESTORED':'LAYOUT_WAIT')+(cycles===100?'_100':'');
            },100);
            </script>""".toByteArray()
        io.execute {while(!server.isClosed)try {server.accept().use {socket->socket.soTimeout=5000;val reader=socket.getInputStream().bufferedReader();reader.readLine();while(!reader.readLine().isNullOrEmpty()){};socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray());write(page);flush()} }}catch(_:java.io.IOException){}}
        try {
            inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser","address")),15000))
            await("block initialized"){!app.blocker.busy&&app.blocker.status!="NOT_INITIALIZED"}
            main {
                previousMode=app.blocker.mode;previousCosmetic=app.blocker.cosmeticEnabled;previousCollapse=app.blocker.collapseEnabled
                app.coordinator.scheduler.tabs.forEach {app.coordinator.close(it.id,true)}
                app.blocker.setCosmetic(true);app.blocker.setCollapse(true);app.blocker.setMode(BlockController.Mode.LITE)
            }
            await("lite acknowledged"){!app.blocker.busy&&app.blocker.status.startsWith("READY_CONFIG_ACK")}
            var id="";var identity:Long?=null
            main {app.coordinator.createTab("$origin/");id=app.coordinator.scheduler.selectedId!!;identity=app.coordinator.scheduler.tab(id)!!.identity}
            await("250px gap and dynamic ads collapse; protected layout preserved"){app.coordinator.scheduler.tab(id)?.title=="LAYOUT_COLLAPSED_100"}
            main {app.blocker.setCollapse(false)}
            await("collapse off restores wrapper height without reload"){app.coordinator.scheduler.tab(id)?.title=="LAYOUT_RESTORED_100"}
            main {app.blocker.setCollapse(true)}
            await("collapse reapplied"){app.coordinator.scheduler.tab(id)?.title=="LAYOUT_COLLAPSED_100"}
            main {app.blocker.toggleException(origin,false)}
            await("site exception restores original layout"){app.coordinator.scheduler.tab(id)?.title=="LAYOUT_RESTORED_100"}
            main {app.blocker.toggleException(origin,false)}
            await("exception undo recollapses"){app.coordinator.scheduler.tab(id)?.title=="LAYOUT_COLLAPSED_100"}
            main {val t=app.coordinator.scheduler.tab(id)!!;assertTrue(app.blocker.startPicker(origin,false) {app.coordinator.scheduler.selectedId==id&&app.coordinator.scheduler.tab(id)?.identity==identity&&app.coordinator.scheduler.tab(id)?.epoch==t.epoch})}
            assertTrue(device.wait(Until.hasObject(By.text("隠す領域をタップ（フォーム・本文は保護）")),10000))
            device.findObject(By.text("PICKER_FIXTURE")).click()
            assertTrue(device.wait(Until.hasObject(By.text("選択範囲を確認してください")),10000))
            device.findObject(By.text("適用")).click()
            await("picker rule saved"){app.blocker.siteRules(origin,false).any {it.endsWith("###picker_fixture")}}
            assertTrue(device.wait(Until.gone(By.text("PICKER_FIXTURE")),10000))
            main {app.blocker.undoPickerRule(false)}
            assertTrue(device.wait(Until.hasObject(By.text("PICKER_FIXTURE")),10000))
            main {app.blocker.setMode(BlockController.Mode.ETP_ONLY)}
            await("disable cleanup restores layout before extension shutdown"){!app.blocker.busy&&app.coordinator.scheduler.tab(id)?.title=="LAYOUT_RESTORED_100"}
            main {assertEquals(identity,app.coordinator.scheduler.tab(id)!!.identity)}
        } finally {
            await("operation idle"){!app.blocker.busy}
            main {if(app.blocker.isException(origin,false))app.blocker.toggleException(origin,false);app.blocker.setCosmetic(previousCosmetic);app.blocker.setCollapse(previousCollapse);app.blocker.setMode(previousMode);app.coordinator.scheduler.tabs.forEach {app.coordinator.close(it.id,true)}}
            server.close();io.shutdownNow()
        }
        // 100 insertion cycles are functional coverage, not a memory/CPU measurement.
    }
}
