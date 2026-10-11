package dev.velocity.browser

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velocity.browser.download.DownloadController
import dev.velocity.browser.tabs.TabRepository
import dev.velocity.browser.tabs.TabRecord
import dev.velocity.browser.security.Protection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CompatibilityIntegrationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext.applicationContext as VelocityApp
    private val device get()=UiDevice.getInstance(inst)
    private fun documents()=By.pkg(java.util.regex.Pattern.compile("(?:com.android|com.google.android).documentsui"))
    private fun main(block:()->Unit)=inst.onMainForTest(block)
    private fun await(message:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<end) { var ok=false;main { ok=condition() };if(ok)return;SystemClock.sleep(100) };fail(message)
    }
    private fun click(text:String) {
        assertTrue("missing $text",device.wait(Until.hasObject(By.text(text)),15000));device.findObject(By.text(text)).click();device.waitForIdle()
    }
    private fun screen(text:String,message:()->String) {
        if(device.wait(Until.hasObject(By.text(text)),15000))return
        val out=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(out)
        fail(message()+"\n"+out.toString("UTF-8").take(22000))
    }
    private fun launch() {
        inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser","address")),15000))
        await("startup") { app.coordinator.scheduler.selectedId!=null }
        main { app.coordinator.externalActivities.cancelAll();app.coordinator.downloads.dismiss();app.coordinator.scheduler.pendingId?.let(app.coordinator::cancelPending);app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) } }
    }
    private fun cleanup() {
        device.setOrientationNatural();device.unfreezeRotation()
        repeat(3) { if(device.hasObject(documents()) || device.hasObject(By.text("BRIDGE_CANCEL"))) { device.pressBack();device.waitForIdle() } }
        main { app.coordinator.externalActivities.cancelAll();app.coordinator.downloads.snapshot().filter { it.active }.forEach { app.coordinator.downloads.cancel(it.id) };app.coordinator.downloads.dismiss();app.coordinator.scheduler.pendingId?.let(app.coordinator::cancelPending);app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) } }
    }
    private class Fixture:AutoCloseable {
        val server=java.net.ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"))
        val url="http://127.0.0.1:${server.localPort}"
        private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
        val authRequests=java.util.concurrent.atomic.AtomicInteger()
        val authAccepted=java.util.concurrent.atomic.AtomicInteger()
        val uploadRequests=java.util.concurrent.atomic.AtomicInteger()
        val uploadBytes=java.util.concurrent.atomic.AtomicInteger()
        val uploadParts=java.util.concurrent.atomic.AtomicInteger()
        val uploadAlpha=java.util.concurrent.atomic.AtomicBoolean()
        init { io.execute { while(!server.isClosed) try { server.accept().use { socket ->
            socket.soTimeout=5000;val input=socket.getInputStream().buffered();val header=StringBuilder()
            while(!header.endsWith("\r\n\r\n")) { val n=input.read();if(n<0)return@use;header.append(n.toChar());if(header.length>16384)return@use }
            val headers=header.toString();val path=headers.lineSequence().first().split(' ')[1].substringBefore('?')
            val length=Regex("(?im)^Content-Length:\\s*([0-9]+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
            if(headers.contains("Expect: 100-continue",true))socket.getOutputStream().apply { write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray());flush() }
            val body=if(headers.contains("Transfer-Encoding: chunked",true)) {
                val bytes=java.io.ByteArrayOutputStream()
                fun line():String { val s=StringBuilder();while(!s.endsWith("\r\n")){val c=input.read();if(c<0)error("incomplete chunk");s.append(c.toChar());if(s.length>4096)error("chunk header too large")};return s.dropLast(2).toString() }
                while(true){val size=line().substringBefore(';').toInt(16);if(size==0){line();break};require(size>0 && bytes.size()+size<=65536);repeat(size){val c=input.read();if(c<0)error("incomplete chunk");bytes.write(c)};require(line().isEmpty())};bytes.toByteArray()
            } else ByteArray(length.coerceAtMost(65536)).also { body -> var position=0;while(position<body.size){val n=input.read(body,position,body.size-position);if(n<0)break;position+=n} }
            var status="200 OK";var extra="";var type="text/html; charset=utf-8"
            val payload=when(path) {
                "/auth" -> { authRequests.incrementAndGet();val encoded=Regex("(?im)^Authorization:\\s*Basic\\s+([^\\s]+)").find(headers)?.groupValues?.get(1);if(encoded!="Zml4dHVyZTpmaXh0dXJl") { status="401 Unauthorized";extra="WWW-Authenticate: Basic realm=\"fixture\"\r\n";"AUTH_REQUIRED" } else {authAccepted.incrementAndGet();"<p>AUTH_OK</p>"} }
                "/upload" -> {uploadRequests.incrementAndGet();uploadBytes.set(body.size);val text=body.toString(Charsets.UTF_8);val fields=Regex("Content-Disposition:\\s*form-data",RegexOption.IGNORE_CASE).findAll(text).count();uploadParts.set(fields);uploadAlpha.set(text.contains("fixture-alpha"));if(uploadAlpha.get() && (fields==1 || fields==2 && text.contains("fixture-beta")))"UPLOAD_OK" else "UPLOAD_BAD"}
                "/download" -> { type="text/plain";extra="Content-Disposition: attachment; filename=\"velocity-download.txt\"\r\n";"download-real-gecko-stream" }
                else -> """<!doctype html><meta charset=utf-8><title>COMPAT_FIXTURE</title><label>DRAFT<input id=draft aria-label=DRAFT></label><input id=one type=file accept=.txt><input id=many type=file accept=text/plain multiple><button onclick="one.click()">PICK_SINGLE</button><button onclick="many.click()">PICK_MULTIPLE</button><a href=/download download>DOWNLOAD_FIXTURE</a><p id=result>READY</p><script>for(const id of ['one','many']) document.getElementById(id).onchange=async function(){const fd=new FormData();for(const f of this.files)fd.append('file',f);const status=await(await fetch('/upload',{method:'POST',body:fd})).text()+'_'+this.files.length;document.getElementById('result').textContent=status;document.title=status+'_'+(document.getElementById('draft').value==='KEEP_SELECTED_FILES'?'DRAFT_OK':'DRAFT_LOST')};async function credentialTest(){const publicKey={challenge:new Uint8Array(32),rpId:location.hostname,allowCredentials:[{id:new Uint8Array(16),type:'public-key'}],timeout:5000};const controller=new AbortController();controller.abort();try{await navigator.credentials.get({publicKey,signal:controller.signal});result.textContent='UNEXPECTED_CREDENTIAL'}catch(e){result.textContent='CANCELED_'+e.name}}async function invalidRp(){try{await navigator.credentials.get({publicKey:{challenge:new Uint8Array(32),rpId:'unrelated.invalid',allowCredentials:[],timeout:5000}});result.textContent='UNEXPECTED_CREDENTIAL'}catch(e){result.textContent='RP_REJECTED_'+e.name}}</script><button onclick=credentialTest()>PASSKEY_CANCEL</button><button onclick=invalidRp()>PASSKEY_INVALID_RP</button>"""
            }.toByteArray()
            socket.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: $type\r\n${extra}Content-Length: ${payload.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray());write(payload);flush() }
        } } catch(_:java.io.IOException) { } } }
        override fun close(){server.close();io.shutdownNow()}
    }
    private fun page(f:Fixture,privateMode:Boolean=false):String {
        var id="";main { app.coordinator.createTab(f.url+"/",privateMode);id=app.coordinator.scheduler.selectedId!! }
        await("fixture page") { app.coordinator.scheduler.tab(id)?.title=="COMPAT_FIXTURE" && app.coordinator.scheduler.selectedId==id }
        assertTrue(device.wait(Until.hasObject(By.text("READY")),15000));return id
    }
    private fun root() {
        assertTrue("system SAF picker",device.wait(Until.hasObject(documents()),15000))
        if(device.hasObject(By.text("alpha.txt")) && device.hasObject(By.text("Velocity fixtures")))return
        if(!device.hasObject(By.text("Velocity fixtures"))) {
            val roots=device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("Open navigation drawer"))
            assertNotNull("system SAF roots button",roots);roots!!.click()
        };click("Velocity fixtures")
    }
    private fun draft()=device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { it.resourceName!="dev.velocity.browser:id/address" }
    private fun uploaded(id:String,count:Int,f:Fixture) {
        val expected="UPLOAD_OK_${count}_DRAFT_OK"
        try { await("real upload title and retained draft") { app.coordinator.scheduler.tab(id)?.title==expected } }
        catch(_:AssertionError) { var title="";main {title=app.coordinator.scheduler.tab(id)?.title.orEmpty()};fail("expected=$expected title=$title requests=${f.uploadRequests.get()} bytes=${f.uploadBytes.get()} parts=${f.uploadParts.get()} alpha=${f.uploadAlpha.get()}") }
        assertEquals(count,f.uploadParts.get());assertTrue(f.uploadAlpha.get())
    }
    @Test fun singleAndMultipleSafAttachmentsActuallyUploadAndRemainProtected() {
        launch();Fixture().use { f -> try {
            val id=page(f);assertNotNull(draft());draft()!!.text="KEEP_SELECTED_FILES";click("PICK_SINGLE");root();click("alpha.txt")
            uploaded(id,1,f)
            main { assertTrue(Protection.UPLOAD in app.coordinator.scheduler.tab(id)!!.protections);assertTrue(app.coordinator.scheduler.liveCount<=2) }
            click("PICK_MULTIPLE");root()
            device.findObject(By.text("alpha.txt")).longClick();click("beta.txt")
            val select=device.findObject(By.res("com.android.documentsui","action_menu_select")) ?: device.findObject(By.res("com.google.android.documentsui","action_menu_select")) ?: device.findObject(By.text(java.util.regex.Pattern.compile("(?i)select"))) ?: device.findObject(By.desc(java.util.regex.Pattern.compile("(?i)select")))
            assertNotNull("multiple file selection action",select);select!!.click()
            uploaded(id,2,f)
            click("PICK_SINGLE");root();device.pressBack()
            uploaded(id,2,f)
            main { assertTrue(Protection.UPLOAD in app.coordinator.scheduler.tab(id)!!.protections) }
            main { app.coordinator.close(id,true) }
            val end=SystemClock.elapsedRealtime()+15000
            while(java.io.File(inst.targetContext.cacheDir,"selected-files").walkTopDown().any { it.isFile } && SystemClock.elapsedRealtime()<end)SystemClock.sleep(100)
            assertFalse(java.io.File(inst.targetContext.cacheDir,"selected-files").walkTopDown().any { it.isFile })
        } finally { cleanup() } }
    }
    @Test fun downloadSavesRealResponseToSafAndCancellationNeverReportsSuccess() {
        launch();Fixture().use { f -> try {
            val id=page(f);click("DOWNLOAD_FIXTURE")
            await("download offer") { app.coordinator.downloads.snapshot().any { it.state==DownloadController.State.OFFERED } }
            click("保存先を選ぶ");root()
            assertTrue("SAF save action",device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("(?i)save"))),15000))
            val save=device.findObject(By.res("android","button1")) ?: device.findObject(By.res("com.android.documentsui","button_save")) ?: device.findObject(By.res("com.google.android.documentsui","button_save")) ?: device.findObject(By.text(java.util.regex.Pattern.compile("(?i)save")))
            assertNotNull("SAF save",save);save!!.click()
            await("download completion") { app.coordinator.downloads.snapshot().any { it.state==DownloadController.State.COMPLETE } }
            var uri:android.net.Uri?=null
            main { val job=app.coordinator.downloads.snapshot().last();uri=job.destination;assertEquals(26L,job.bytes);assertTrue(app.coordinator.scheduler.liveCount<=2) }
            assertEquals("download-real-gecko-stream",inst.targetContext.contentResolver.openInputStream(uri!!)!!.bufferedReader().use { it.readText() })
            click("閉じる");main { app.coordinator.select(id);app.coordinator.navigate(f.url+"/") }
            assertTrue(device.wait(Until.hasObject(By.text("DOWNLOAD_FIXTURE")),15000));click("DOWNLOAD_FIXTURE")
            await("second offer") { app.coordinator.downloads.snapshot().last().state==DownloadController.State.OFFERED }
            main { app.coordinator.downloads.cancel(app.coordinator.downloads.snapshot().last().id);assertEquals(DownloadController.State.CANCELED,app.coordinator.downloads.snapshot().last().state) }
            click("閉じる")
        } finally { cleanup() } }
    }
    @Test fun httpAuthenticationCancelThenRetryUsesExplicitCredentials() {
        launch();Fixture().use { f -> try {
            main { app.coordinator.createTab(f.url+"/auth") }
            assertTrue("initial auth dialog",device.wait(Until.hasObject(By.text("HTTP認証")),15000))
            click("キャンセル")
            assertTrue("canceled request must settle before retry",device.wait(Until.hasObject(By.text("更新")),15000))
            main { app.coordinator.navigate(f.url+"/auth?retry=1") }
            assertTrue("retry auth dialog, requests=${f.authRequests.get()}",device.wait(Until.hasObject(By.text("HTTP認証")),15000))
            device.findObject(By.hint("ユーザー名")).text="fixture"
            device.findObject(By.hint("パスワード")).text="fixture"
            click("ログイン")
            screen("AUTH_OK") { "real auth: requests=${f.authRequests.get()} accepted=${f.authAccepted.get()}" }
            assertTrue(f.authRequests.get()>=2)
            main { assertTrue(app.coordinator.scheduler.liveCount<=2) }
        } finally { cleanup() } }
    }
    @Test fun webauthnAbortAndInvalidRpAreRealGeckoErrorsInNormalAndPrivateTabs() {
        launch();Fixture().use { f -> try {
            for(privateMode in listOf(false,true)) {
                main { app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id,true) } }
                val id=page(f,privateMode)
                click("PASSKEY_CANCEL")
                assertTrue(device.wait(Until.hasObject(By.text("CANCELED_AbortError")),15000))
                click("PASSKEY_INVALID_RP")
                assertTrue(device.wait(Until.hasObject(By.text("RP_REJECTED_SecurityError")),15000))
                main { assertNotNull(app.coordinator.scheduler.tab(id));assertTrue(app.coordinator.scheduler.liveCount<=2) }
                if(privateMode) {
                    click("PICK_SINGLE");device.waitForIdle();assertFalse("private upload must not launch disk staging",device.hasObject(documents()))
                    main { assertEquals(id,app.coordinator.scheduler.selectedId);assertFalse(Protection.UPLOAD in app.coordinator.scheduler.tab(id)!!.protections) }
                }
            }
        } finally { cleanup() } }
    }
    @Test fun runtimeActivityBridgeReturnsCancellationAndRetryAcrossRotationWithoutCredentials() {
        launch();Fixture().use { f -> try {
            val id=page(f)
            val intent=Intent().setClassName(inst.targetContext,"dev.velocity.browser.fixture.BridgeResultActivity")
            val pending=PendingIntent.getActivity(inst.targetContext,91,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            var error:Throwable?=null;val canceled=java.util.concurrent.CountDownLatch(1)
            main { app.coordinator.runtimeActivities.delegate.onStartActivityForResult(pending)!!.accept({ canceled.countDown() },{ error=it;canceled.countDown() }) }
            click("BRIDGE_CANCEL");assertTrue(canceled.await(10,TimeUnit.SECONDS));assertNotNull(error)
            val completed=java.util.concurrent.CountDownLatch(1);var data:Intent?=null
            main { app.coordinator.runtimeActivities.delegate.onStartActivityForResult(pending)!!.accept({ data=it;completed.countDown() },{ completed.countDown() }) }
            device.setOrientationLeft();device.waitForIdle();click("BRIDGE_RETURN")
            assertTrue(completed.await(10,TimeUnit.SECONDS));assertTrue(data?.getBooleanExtra("bridge_fixture",false)==true)
            main { assertEquals(id,app.coordinator.scheduler.selectedId);assertTrue(app.coordinator.scheduler.liveCount<=2);assertFalse(Protection.WEBAUTHN in app.coordinator.scheduler.tab(id)!!.protections) }
            val obsolete=java.util.concurrent.CountDownLatch(1);val callbacks=java.util.concurrent.atomic.AtomicInteger();var obsoleteError:Throwable?=null
            main { app.coordinator.runtimeActivities.delegate.onStartActivityForResult(pending)!!.accept({ callbacks.incrementAndGet();obsolete.countDown() },{ obsoleteError=it;callbacks.incrementAndGet();obsolete.countDown() }) }
            assertTrue(device.wait(Until.hasObject(By.text("BRIDGE_RETURN")),15000))
            main { app.coordinator.navigate(f.url+"/?different-document=1") }
            assertTrue(obsolete.await(10,TimeUnit.SECONDS));assertNotNull(obsoleteError);click("BRIDGE_RETURN")
            assertEquals("late Activity result must not complete an obsolete request twice",1,callbacks.get())
        } finally { cleanup() } }
    }
    @Test fun repositoryLatestSnapshotWinsAndPreservesOrderWithoutPrivateRecords() {
        val repo=TabRepository(inst.targetContext,"compat_repository_test")
        repeat(50) { n -> repo.save(listOf(TabRecord("second","https://example.test/$n"),TabRecord("first","about:blank"),TabRecord("private","https://private.invalid/",true)),"second") }
        val latch=java.util.concurrent.CountDownLatch(1);var stored:dev.velocity.browser.tabs.StoredTabs?=null
        repo.load { stored=it;latch.countDown() };assertTrue(latch.await(10,TimeUnit.SECONDS))
        assertEquals(listOf("second","first"),stored!!.tabs.map { it.id });assertEquals("https://example.test/49",stored!!.tabs.first().url);assertEquals("second",stored!!.selectedId)
    }
}
