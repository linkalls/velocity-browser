package dev.velocity.browser
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velocity.browser.tabs.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserIntegrationTest {
    @Test fun externalIntentCreatesHttpTabAndRotationRetainsCoordinator() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(inst)
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
        val owner = (context.applicationContext as VelocityApp).coordinator
        inst.onMainForTest {
            assertTrue(owner.scheduler.tabs.any { it.url == "https://example.com/" })
            assertTrue(owner.scheduler.liveCount <= 2)
        }
        device.setOrientationLeft(); device.waitForIdle(); device.setOrientationNatural(); device.unfreezeRotation()
        inst.onMainForTest { assertSame(owner, (context.applicationContext as VelocityApp).coordinator); assertTrue(owner.scheduler.liveCount <= 2) }
    }
    @Test fun repositoryFiltersPrivateTabs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TabRepository(context, "repository_test")
        repository.save(listOf(TabRecord("normal", "https://example.com/"), TabRecord("private", "https://secret.example/", true), TabRecord("addon", "moz-extension://test/options", extensionBase = "moz-extension://test/")), "private")
        val latch = java.util.concurrent.CountDownLatch(1)
        var restored: StoredTabs? = null
        repository.load { restored = it; latch.countDown() }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("normal"), restored!!.tabs.map { it.id }); assertNull(restored!!.selectedId)
    }
    @Test fun unknownExternalSchemeCannotBecomeBrowserTab() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("javascript:alert(1)"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(inst)
        assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
        inst.onMainForTest {
            assertTrue((context.applicationContext as VelocityApp).coordinator.scheduler.tabs.none { it.url.startsWith("javascript:") })
        }
    }
    @Test fun formTabSurvivesTwentyItemsAndDiscardCandidateTick() {
        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val body = "<!doctype html><meta charset=utf-8><title>フォーム試験</title><label>未保存入力<input id=draft aria-label=未保存入力></label>".toByteArray(Charsets.UTF_8)
        executor.execute {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: java.io.IOException) { }
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val owner = (context.applicationContext as VelocityApp).coordinator
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
            lateinit var formId: String
            var identity: Long? = null
            inst.onMainForTest {
                owner.scheduler.tabs.forEach { owner.close(it.id, true) }
                owner.createTab("http://127.0.0.1:${server.localPort}/")
                formId = owner.scheduler.selectedId!!; identity = owner.scheduler.tab(formId)!!.identity
            }
            assertTrue(device.wait(Until.hasObject(By.text("未保存入力")), 15000))
            fun formField() = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { it.resourceName != "dev.velocity.browser:id/address" }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (formField() == null && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
            val field = formField() ?: throw AssertionError("Gecko form accessibility input not found")
            field.text = "入力保持を検証"
            inst.onMainForTest {
                repeat(19) { owner.createTab() }
                owner.dispatch(Event.Tick(android.os.SystemClock.elapsedRealtime() + 120001))
                owner.memoryPressure()
                assertNull(owner.scheduler.pendingId);assertTrue(owner.scheduler.tabs.size >= 20)
                assertEquals(identity, owner.scheduler.tab(formId)!!.identity)
                owner.select(formId)
            }
            // GeckoView reattaches asynchronously; idle native UI does not mean its
            // accessibility tree is ready. Require the preserved value, not just a field.
            assertTrue("Preserved draft must reappear after selecting its live session",
                device.wait(Until.hasObject(By.clazz("android.widget.EditText").text("入力保持を検証")), 10000))
            assertEquals("入力保持を検証", formField()?.text)
        } finally {
            inst.onMainForTest { owner.scheduler.tabs.forEach { owner.close(it.id, true) } }
            server.close(); executor.shutdownNow()
        }
    }

}
