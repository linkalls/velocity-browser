package dev.velocity.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.velocity.browser.R
import dev.velocity.browser.engine.GeckoRuntimeHolder
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.util.WeakHashMap

class ThemeController(private val context: Context) {
    enum class Mode { SYSTEM, LIGHT, DARK }
    private val prefs = context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    var mode = runCatching { Mode.valueOf(prefs.getString("mode",Mode.SYSTEM.name)!!) }.getOrDefault(Mode.SYSTEM); private set
    private val listeners = linkedSetOf<() -> Unit>()
    private val dialogs = WeakHashMap<Dialog, Boolean>()
    fun dark() = when (mode) { Mode.LIGHT -> false; Mode.DARK -> true; Mode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    fun palette() = BrowserPalette.resolve(context,dark())
    fun style() = if (dark()) R.style.AppThemeDark else R.style.AppThemeLight
    @GeckoRuntimeSettings.ColorScheme
    fun geckoScheme() = when (mode) { Mode.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM; Mode.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT; Mode.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK }
    fun select(next: Mode) {
        mode = next; prefs.edit().putString("mode", next.name).apply()
        GeckoRuntimeHolder.applyColorScheme(geckoScheme()); notifyChange()
    }
    fun onSystemChanged() { if (mode == Mode.SYSTEM) notifyChange() }
    fun observe(listener: () -> Unit) { listeners += listener }
    fun remove(listener: () -> Unit) { listeners -= listener }
    private fun notifyChange() {
        dialogs.entries.toList().forEach { (dialog,danger) -> if (dialog.isShowing) { (dialog as? AlertDialog)?.listView?.invalidateViews(); tintDialog(dialog,danger) } }
        listeners.toList().forEach { it() }
    }
    fun dialogContext(activity: Activity) = ContextThemeWrapper(activity,style())
    fun builder(activity: Activity) = AlertDialog.Builder(dialogContext(activity))
    fun show(builder: AlertDialog.Builder, danger: Boolean = false): AlertDialog {
        val dialog = builder.create(); dialog.show(); track(dialog,danger)
        return dialog
    }
    fun track(dialog: Dialog, danger: Boolean = false) { dialogs[dialog] = danger; tintDialog(dialog,danger) }
    fun dismissFor(activity: Activity) {
        fun owner(c: Context): Context = if (c is android.content.ContextWrapper && c !== activity) owner(c.baseContext) else c
        dialogs.keys.toList().filter { owner(it.context) === activity }.forEach { it.dismiss(); dialogs.remove(it) }
    }
    fun tintDialog(dialog: Dialog, danger: Boolean = false) {
        val p = palette()
        dialog.context.theme.applyStyle(style(),true)
        dialog.window?.setBackgroundDrawable(context.rounded(p.surface,20))
        fun visit(view: View) {
            when (view) {
                is TextView -> { view.applyTextPalette(p); if (view is Button) { view.setTextColor(if (danger && view.id == android.R.id.button1) p.danger else p.accent); view.backgroundTintList = android.content.res.ColorStateList.valueOf(p.pressed) }; if (view is CompoundButton) view.buttonTintList = android.content.res.ColorStateList.valueOf(p.accent) }
                is ListView -> { view.setBackgroundColor(p.surface); view.selector = ColorDrawable(p.pressed); view.divider = ColorDrawable(p.border) }
                is ViewGroup -> if (view.background != null) view.setBackgroundColor(p.surface)
            }
            if (view is ViewGroup) for (n in 0 until view.childCount) visit(view.getChildAt(n))
        }
        dialog.window?.decorView?.let(::visit)
        if (context is dev.velocity.browser.VelocityApp && context.coordinator.scheduler.tabs.any { it.privateMode }) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    @Suppress("DEPRECATION")
    fun applyWindow(activity: Activity) {
        val p = palette(); val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(p.background)); window.statusBarColor = p.background; window.navigationBarColor = p.background
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller?.setSystemBarsAppearance(if (p.dark) 0 else mask,mask)
        } else {
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (p.dark) window.decorView.systemUiVisibility and mask.inv() else window.decorView.systemUiVisibility or mask
        }
    }
}
