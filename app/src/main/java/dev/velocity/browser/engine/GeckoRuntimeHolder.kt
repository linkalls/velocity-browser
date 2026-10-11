package dev.velocity.browser.engine
import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeHolder {
    private var runtime: GeckoRuntime? = null
    fun get(context: Context): GeckoRuntime {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        return runtime ?: GeckoRuntime.create(context.applicationContext,
            GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).consoleOutput(false).build()).also { runtime = it; (context.applicationContext as dev.velocity.browser.VelocityApp).blocker.attach(it) }
    }
}
