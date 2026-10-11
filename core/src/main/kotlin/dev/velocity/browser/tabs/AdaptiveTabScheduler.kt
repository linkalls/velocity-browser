package dev.velocity.browser.tabs

import dev.velocity.browser.security.Protection
import dev.velocity.browser.util.Clock

sealed interface Event {
    data class CreateTab(val id: String, val url: String, val privateMode: Boolean = false) : Event
    data class SelectTab(val id: String) : Event
    data class HideTab(val id: String) : Event
    data class CloseTab(val id: String, val confirmed: Boolean = false) : Event
    data class Ready(val id: String, val identity: Long) : Event
    data class NavigationStarted(val id: String, val identity: Long, val url: String) : Event
    data class LocationChanged(val id: String, val identity: Long, val url: String) : Event
    data class TitleChanged(val id: String, val identity: Long, val title: String) : Event
    data class SessionStateChanged(val id: String, val identity: Long, val epoch: Long, val token: String) : Event
    data class RiskChanged(val id: String, val identity: Long, val protection: Protection, val enabled: Boolean) : Event
    // 保存通知とは別の証拠。v0.1 adapterは未操作about:blankにだけ発行する。
    data class SafeCheckpoint(val id: String, val identity: Long, val epoch: Long, val revision: Long, val generation: Long) : Event
    data class RestoreFailed(val id: String, val identity: Long) : Event
    data class Crashed(val id: String, val identity: Long) : Event
    data class MemoryPressure(val severe: Boolean = true) : Event
    data class AppForegroundChanged(val foreground: Boolean) : Event
    data class Tick(val elapsedRealtimeMs: Long) : Event
}
sealed interface Command {
    data class CreateSession(val id: String, val identity: Long, val privateMode: Boolean, val url: String, val snapshot: String?) : Command
    data class AttachSessionToView(val id: String) : Command
    data class DetachSessionFromView(val id: String) : Command
    data class SetSessionActive(val id: String, val active: Boolean) : Command
    data class RequestSessionStateFlush(val id: String, val identity: Long, val epoch: Long, val revision: Long, val generation: Long) : Command
    data class CloseSession(val id: String, val identity: Long) : Command
    data object PersistTabMetadata : Command
    data class ShowEvictionBlockedNotice(val id: String) : Command
    data class ConfirmClose(val id: String) : Command
    data class FallbackReload(val id: String, val url: String) : Command
}

/** Main threadなど単一の所有スレッドから呼ぶ。副作用はCommandのみ。 */
class AdaptiveTabScheduler(val config: AdaptiveConfig, private val clock: Clock) {
    private val records = linkedMapOf<String, TabRecord>()
    val tabs: List<TabRecord> get() = records.values.map { it.detachedCopy() }
    var selectedId: String? = null; private set
    var foreground = false; private set
    var adaptiveEnabled = true
    private var nextIdentity = 0L
    val liveCount get() = records.values.count { it.live }
    fun tab(id: String): TabRecord? = records[id]?.detachedCopy()

    fun dispatch(event: Event): List<Command> {
        val out = mutableListOf<Command>()
        fun persist(t: TabRecord) { if (!t.privateMode) out += Command.PersistTabMetadata }
        when (event) {
            is Event.CreateTab -> if (!records.containsKey(event.id)) {
                records[event.id] = TabRecord(event.id, event.url, event.privateMode)
                persist(records.getValue(event.id))
            }
            is Event.SelectTab -> select(event.id, out)
            is Event.HideTab -> records[event.id]?.takeIf { it.id == selectedId }?.let {
                hide(it, out); selectedId = null
            }
            is Event.CloseTab -> records[event.id]?.let { t ->
                if (t.live && !t.safelySaved && !event.confirmed) out += Command.ConfirmClose(t.id)
                else {
                    val wasSelected = selectedId == t.id
                    if (wasSelected) { out += Command.DetachSessionFromView(t.id); selectedId = null }
                    t.identity?.let { out += Command.CloseSession(t.id, it) }
                    records.remove(t.id); persist(t)
                    if (wasSelected) records.values.firstOrNull { it.live }?.let { select(it.id, out) }
                }
            }
            is Event.Ready -> matching(event.id, event.identity)?.let {
                it.state = if (selectedId == it.id) TabState.ACTIVE else TabState.WARM
                out += Command.SetSessionActive(it.id, selectedId == it.id && foreground)
            }
            is Event.NavigationStarted -> matching(event.id, event.identity)?.let {
                it.url = event.url; it.epoch++; it.revision++; it.snapshot = null
                it.snapshotEpoch = -1; it.certifiedRevision = null
                it.protections += Protection.UNKNOWN; persist(it)
            }
            is Event.LocationChanged -> matching(event.id, event.identity)?.let { it.url = event.url; persist(it) }
            is Event.TitleChanged -> matching(event.id, event.identity)?.let { it.title = event.title; persist(it) }
            is Event.SessionStateChanged -> matching(event.id, event.identity)?.takeIf { it.epoch == event.epoch }?.let {
                it.snapshot = event.token; it.snapshotEpoch = event.epoch
                // callbackに要求IDがない。保存の確証にはしない。
            }
            is Event.RiskChanged -> matching(event.id, event.identity)?.let {
                val changed = if (event.enabled) it.protections.add(event.protection) else it.protections.remove(event.protection)
                if (changed) { it.revision++; it.certifiedRevision = null }
            }
            is Event.SafeCheckpoint -> matching(event.id, event.identity)?.takeIf {
                it.epoch == event.epoch && it.revision == event.revision && it.saveGeneration == event.generation && it.protections.isEmpty()
            }?.let { it.certifiedRevision = it.revision; if (it.state == TabState.SAVING) it.state = TabState.WARM }
            is Event.RestoreFailed -> matching(event.id, event.identity)?.let {
                it.snapshot = null; it.snapshotEpoch = -1
                out += Command.FallbackReload(it.id, it.url)
            }
            is Event.Crashed -> matching(event.id, event.identity)?.let {
                if (selectedId == it.id) out += Command.DetachSessionFromView(it.id)
                out += Command.CloseSession(it.id, event.identity)
                it.identity = null; it.state = TabState.FAILED; it.certifiedRevision = null; it.snapshot = null
                if (selectedId == it.id) selectedId = null
            }
            is Event.AppForegroundChanged -> {
                foreground = event.foreground
                records.values.filter { it.live }.forEach { out += Command.SetSessionActive(it.id, foreground && it.id == selectedId) }
                if (foreground) scan(clock.elapsedRealtimeMs(), false, out)
            }
            is Event.MemoryPressure -> if (config.aggressiveMemoryReclaim && adaptiveEnabled) scan(clock.elapsedRealtimeMs(), event.severe, out)
            is Event.Tick -> if (foreground) scan(event.elapsedRealtimeMs, false, out)
        }
        check(liveCount <= config.maxLiveTabs)
        check(records.values.count { it.state == TabState.ACTIVE } <= 1)
        return out.distinct()
    }
    private fun matching(id: String, identity: Long) = records[id]?.takeIf { it.identity == identity }
    private fun select(id: String, out: MutableList<Command>) {
        val t = records[id] ?: return
        if (selectedId == id && t.live) return
        if (!t.live && liveCount >= config.maxLiveTabs) {
            val victim = records.values.filter { it.live && it.id != selectedId && it.safelySaved }.minByOrNull { it.lastUsed }
            if (victim == null) {
                records.values.filter { it.live && it.id != selectedId }.forEach { requestSave(it, out) }
                out += Command.ShowEvictionBlockedNotice(id); return
            }
            evict(victim, out)
        }
        selectedId?.let { records[it] }?.let { hide(it, out) }
        selectedId = id; t.lastUsed = clock.elapsedRealtimeMs(); t.hiddenAt = null
        if (!t.live) {
            val identity = ++nextIdentity; t.identity = identity; t.state = TabState.RESTORING
            out += Command.CreateSession(id, identity, t.privateMode, t.url, t.snapshot.takeIf { t.snapshotEpoch == t.epoch })
        } else t.state = TabState.ACTIVE
        out += Command.AttachSessionToView(id)
        out += Command.SetSessionActive(id, foreground)
        if (!t.privateMode) out += Command.PersistTabMetadata
    }
    private fun hide(t: TabRecord, out: MutableList<Command>) {
        out += Command.DetachSessionFromView(t.id)
        out += Command.SetSessionActive(t.id, false)
        if (t.live) { t.state = TabState.WARM; t.hiddenAt = clock.elapsedRealtimeMs(); requestSave(t, out) }
    }
    private fun requestSave(t: TabRecord, out: MutableList<Command>) {
        if (!t.live || t.safelySaved || t.state == TabState.SAVING) return
        t.state = TabState.SAVING; t.saveGeneration++
        out += Command.RequestSessionStateFlush(t.id, t.identity!!, t.epoch, t.revision, t.saveGeneration)
    }
    private fun scan(now: Long, pressure: Boolean, out: MutableList<Command>) {
        if (!adaptiveEnabled) return
        records.values.filter { it.live && it.id != selectedId && (pressure || it.hiddenAt?.let { at -> now - at >= config.discardAfterMs } == true) }.forEach {
            if (it.safelySaved) evict(it, out) else requestSave(it, out)
        }
    }
    private fun evict(t: TabRecord, out: MutableList<Command>) {
        check(t.id != selectedId && t.safelySaved)
        out += Command.CloseSession(t.id, t.identity!!)
        t.identity = null; t.state = TabState.COLD; t.hiddenAt = null
    }
}
