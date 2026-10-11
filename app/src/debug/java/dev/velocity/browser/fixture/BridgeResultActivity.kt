package dev.velocity.browser.fixture
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
/** Tests Android result transport only. Does not generate or return WebAuthn credentials. */
class BridgeResultActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        panel.addView(Button(this).apply { text="BRIDGE_RETURN";setOnClickListener { setResult(RESULT_OK,Intent().putExtra("bridge_fixture",true));finish() } })
        panel.addView(Button(this).apply { text="BRIDGE_CANCEL";setOnClickListener { setResult(RESULT_CANCELED);finish() } })
        setContentView(panel)
    }
}
