package dev.velocity.benchmark
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private fun startup(mode: StartupMode) = rule.measureRepeated(
        packageName = "dev.velocity.browser", metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 30, startupMode = mode, setupBlock = { pressHome() }
    ) { startActivityAndWait(); check(device.wait(Until.hasObject(By.res("dev.velocity.browser", "address")), 10_000)) }
    @Test fun cold() = startup(StartupMode.COLD)
    @Test fun warm() = startup(StartupMode.WARM)
    @Test fun hot() = startup(StartupMode.HOT)
}
