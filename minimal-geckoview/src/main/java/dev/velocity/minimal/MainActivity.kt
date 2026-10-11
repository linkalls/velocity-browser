package dev.velocity.minimal
import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.*
class MainActivity : Activity() {
    companion object { private var runtime: GeckoRuntime? = null }
    private lateinit var session: GeckoSession
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val view = GeckoView(this); setContentView(view)
        val engine = runtime ?: GeckoRuntime.create(applicationContext).also { runtime = it }
        session = GeckoSession(); session.open(engine); view.setSession(session); session.loadUri("https://example.com/")
    }
    override fun onDestroy() { session.close(); super.onDestroy() }
}
