package dev.velocity.browser.security

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import dev.velocity.browser.R
import dev.velocity.browser.engine.GeckoSessionAdapter
import dev.velocity.browser.tabs.AdaptiveTabCoordinator
import org.mozilla.geckoview.*

/** サイトの許可とAndroid権限は両方必須。不可視タブとライフサイクル終了は拒否。 */
class PermissionController(private val owner: AdaptiveTabCoordinator) {
    private val pending = mutableMapOf<GeckoSessionAdapter, MutableList<() -> Unit>>()
    fun cancelFor(a: GeckoSessionAdapter) { pending.remove(a)?.toList()?.forEach { it() } }
    fun cancelAll() { pending.keys.toList().forEach { cancelFor(it) } }
    private fun ask(a: GeckoSessionAdapter, message: String, done: (Boolean) -> Unit) {
        val host = owner.host
        if (host == null || owner.scheduler.selectedId != a.id || !owner.scheduler.foreground) { done(false); return }
        var finished = false
        var dialog: AlertDialog? = null
        lateinit var cancel: () -> Unit
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true; pending[a]?.remove(cancel); dialog?.dismiss()
            done(ok && owner.isCurrent(a.id, a.identity) && owner.scheduler.selectedId == a.id && owner.scheduler.foreground)
        }
        cancel = { finish(false) }
        pending.getOrPut(a) { mutableListOf() }.add(cancel)
        a.risk(Protection.PERMISSION, true)
        dialog = AlertDialog.Builder(host.activity).setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> finish(true) }
            .setNegativeButton(R.string.no) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }.create()
        dialog.show()
        // UNKNOWNを残すので、許可直後やキャンセル後も安全性を過大評価しない。
    }
    fun delegate(a: GeckoSessionAdapter) = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(s: GeckoSession, permissions: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback) {
            val allowed = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            val host = owner.host
            if (permissions == null || host == null || permissions.any { it !in allowed } || owner.scheduler.selectedId != a.id) { callback.reject(); return }
            a.risk(Protection.PERMISSION, true)
            host.requestAndroidPermissions(permissions) { ok ->
                if (ok && owner.isCurrent(a.id, a.identity) && owner.scheduler.selectedId == a.id && owner.scheduler.foreground) callback.grant() else callback.reject()
            }
        }
        override fun onContentPermissionRequest(s: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
            val result = GeckoResult<Int>()
            // v0.1は位置情報のみ。永続storage/通知等を誤って許可しない。
            if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                owner.notice(R.string.unsupported)
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            val host = owner.host ?: return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            ask(a, host.activity.getString(R.string.site_permission, perm.uri, perm.permission)) { yes ->
                if (!yes) result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                else host.requestAndroidPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { ok ->
                    result.complete(if (ok && owner.isCurrent(a.id, a.identity) && owner.scheduler.selectedId == a.id && owner.scheduler.foreground) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
            }
            return result
        }
        override fun onMediaPermissionRequest(s: GeckoSession, uri: String, video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?, callback: GeckoSession.PermissionDelegate.MediaCallback) {
            val host = owner.host
            if (host == null) { callback.reject(); return }
            val camera = video?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA }
            val microphone = audio?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE }
            if ((video != null && camera == null) || (audio != null && microphone == null)) { callback.reject(); owner.notice(R.string.unsupported); return }
            ask(a, host.activity.getString(R.string.media_permission, uri)) { yes ->
                if (!yes) callback.reject() else {
                    val required = buildList { if (camera != null) add(Manifest.permission.CAMERA); if (microphone != null) add(Manifest.permission.RECORD_AUDIO) }.toTypedArray()
                    host.requestAndroidPermissions(required) { ok ->
                        if (ok && owner.isCurrent(a.id, a.identity) && owner.scheduler.selectedId == a.id && owner.scheduler.foreground) {
                            if (camera != null) a.risk(Protection.CAMERA, true)
                            if (microphone != null) a.risk(Protection.MICROPHONE, true)
                            callback.grant(camera, microphone)
                        } else callback.reject()
                    }
                }
            }
        }
    }
}
