package dev.velocity.browser
import android.app.Application
import android.content.ComponentCallbacks2
import dev.velocity.browser.tabs.AdaptiveTabCoordinator

class VelocityApp : Application() {
    val homeInputState=dev.velocity.browser.ui.HomeInputState()
    val appearance by lazy { dev.velocity.browser.ui.ThemeController(this) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); appearance.onSystemChanged() }
    val blocker by lazy { dev.velocity.browser.block.BlockController(this) }
    val coordinator by lazy { AdaptiveTabCoordinator(this).also { c -> blocker.onUntrustedExtension = c::untrustedExtensionInstalled; blocker.openExtensionPage = c::createExtensionTab; blocker.onChanged = c::render; blocker.onOptionsUnavailable = c::notice } }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) coordinator.memoryPressure()
    }
}
