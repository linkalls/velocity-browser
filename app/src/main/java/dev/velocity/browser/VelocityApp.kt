package dev.velocity.browser
import android.app.Application
import android.content.ComponentCallbacks2
import dev.velocity.browser.tabs.AdaptiveTabCoordinator

class VelocityApp : Application() {
    val blocker by lazy { dev.velocity.browser.block.BlockController(this) }
    val coordinator by lazy { AdaptiveTabCoordinator(this) }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) coordinator.memoryPressure()
    }
}
