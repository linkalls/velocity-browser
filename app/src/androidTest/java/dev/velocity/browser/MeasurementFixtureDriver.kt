package dev.velocity.browser

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test

/** Optional profiler driver. UiAutomation stays alive during capture so Gecko's tree is available. */
class MeasurementFixtureDriver {
    @RequiresExternalFixture
    @Test fun holdRenderedFixture() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val scene=InstrumentationRegistry.getArguments().getString("scene")
        require(scene=="static"||scene=="dynamic")
        val device=UiDevice.getInstance(inst)
        val launch=device.executeShellCommand("am start -W -a android.intent.action.VIEW -d http://127.0.0.1:8181/$scene dev.velocity.browser/.MainActivity")
        java.io.File(inst.targetContext.cacheDir,"velocity-profile-launch.txt").writeText(launch)
        assertTrue("Actual fixture must be accessible before recording",device.wait(Until.hasObject(By.textContains("Fixture row")),30000))
        device.executeShellCommand("touch /data/local/tmp/velocity-fixture-ready")
        Thread.sleep(30000)
    }
}
