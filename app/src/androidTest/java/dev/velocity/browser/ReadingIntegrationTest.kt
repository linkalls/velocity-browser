package dev.velocity.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velocity.browser.tabs.TabState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingIntegrationTest {
    @Test fun twentyRealReadingPagesReplayWithoutThirdLiveSessionOrRefetch() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as VelocityApp
        val device = UiDevice.getInstance(inst)
        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        io.execute {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    val body = "<title>READING_PAGE_$path</title><p>READING_BODY_$path</p><form><textarea>DO_NOT_COPY_DRAFT</textarea></form><script>document.write('SCRIPT_RAN')</script>".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                    }
                }
            } catch (_: java.io.IOException) { }
        }
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false; inst.runOnMainSync { ready = condition() }
                if (ready) return
                SystemClock.sleep(100)
            }; fail("reading page did not become safely replayable")
        }
        try {
            inst.targetContext.startActivity(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
            waitFor { app.blocker.status != "NOT_INITIALIZED" && !app.blocker.busy }
            var allowed = false; inst.runOnMainSync { allowed = app.blocker.readingAllowed() }
            assumeTrue("External extension installed: reading mode unavailable", allowed)
            inst.runOnMainSync { app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id, true) } }
            var firstId = ""; var firstIdentity: Long? = null
            repeat(20) { n ->
                val url = "http://127.0.0.1:${server.localPort}/$n"
                inst.runOnMainSync { app.coordinator.createReadingTab(url) }
                waitFor { app.coordinator.scheduler.tabs.any { it.url == url && it.safelySaved && it.id == app.coordinator.scheduler.selectedId } }
                assertTrue(device.wait(Until.hasObject(By.textContains("READING_BODY_/$n")), 15000))
                inst.runOnMainSync {
                    assertTrue(app.coordinator.scheduler.liveCount <= 2)
                    if (n == 0) { firstId = app.coordinator.scheduler.selectedId!!; firstIdentity = app.coordinator.scheduler.tab(firstId)!!.identity }
                }
            }
            val count = requests.get()
            inst.runOnMainSync {
                assertEquals(TabState.COLD, app.coordinator.scheduler.tab(firstId)!!.state)
                app.coordinator.select(firstId)
            }
            waitFor { app.coordinator.scheduler.tab(firstId)!!.safelySaved }
            assertTrue(device.wait(Until.hasObject(By.textContains("READING_BODY_/0")), 15000))
            assertFalse(device.hasObject(By.textContains("DO_NOT_COPY_DRAFT")))
            assertFalse(device.hasObject(By.textContains("SCRIPT_RAN")))
            inst.runOnMainSync {
                assertNotEquals(firstIdentity, app.coordinator.scheduler.tab(firstId)!!.identity)
                assertTrue(app.coordinator.scheduler.liveCount <= 2)
            }
            assertEquals("replay must use retained document", count, requests.get())
        } finally {
            inst.runOnMainSync { app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id, true) } }
            server.close(); io.shutdownNow()
        }
    }

    @Test fun privateReadingRequestDoesNotCreateNormalMetadata() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as VelocityApp
        inst.targetContext.startActivity(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(UiDevice.getInstance(inst).wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 15000))
        inst.runOnMainSync {
            app.coordinator.scheduler.tabs.forEach { app.coordinator.close(it.id, true) }
            app.coordinator.createTab(privateMode = true)
            val id = app.coordinator.scheduler.selectedId
            app.coordinator.createReadingTab("https://never-fetch.example/private-reading")
            assertEquals(id, app.coordinator.scheduler.selectedId)
            assertEquals(1, app.coordinator.scheduler.tabs.size)
            assertTrue(app.coordinator.scheduler.tabs.single().privateMode)
            assertFalse(app.coordinator.scheduler.tabs.any { it.readOnly })
            app.coordinator.close(id!!, true)
        }
    }
}
