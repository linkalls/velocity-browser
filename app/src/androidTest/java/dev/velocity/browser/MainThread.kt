package dev.velocity.browser

import android.app.Instrumentation

/** Assert on the test thread, so one failed UI assertion does not kill the app/test run. */
internal fun Instrumentation.onMainForTest(action: () -> Unit) {
    var failure: Throwable? = null
    runOnMainSync {
        try { action() } catch (error: Throwable) { failure = error }
    }
    failure?.let { throw it }
}
