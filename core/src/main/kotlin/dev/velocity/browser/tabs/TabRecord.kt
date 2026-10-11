package dev.velocity.browser.tabs
import dev.velocity.browser.security.Protection

enum class TabState { COLD, RESTORING, ACTIVE, WARM, SAVING, FAILED }
data class TabRecord(
    val id: String, var url: String, val privateMode: Boolean = false,
    val readOnly: Boolean = false, val extensionBase: String? = null, var replayToken: String? = null,
    var title: String = "", var pinned: Boolean = false, var state: TabState = TabState.COLD,
    var identity: Long? = null, var epoch: Long = 0, var revision: Long = 0,
    var hiddenAt: Long? = null, var lastUsed: Long = 0,
    var snapshot: String? = null, var snapshotEpoch: Long = -1,
    var saveGeneration: Long = 0, var certifiedRevision: Long? = null,
    val protections: MutableSet<Protection> = mutableSetOf(Protection.UNKNOWN)
) {
    val live get() = identity != null
    val safelySaved get() = certifiedRevision == revision && protections.isEmpty() && (!readOnly || replayToken != null)
    fun detachedCopy() = copy(protections = protections.toMutableSet())
}
