package dev.velocity.browser.security

import android.content.Intent
import dev.velocity.browser.tabs.AdaptiveTabCoordinator
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime

/** Gecko owns RP/origin validation and credential encoding; this bridge only launches its PendingIntent. */
class RuntimeActivityController(private val owner: AdaptiveTabCoordinator) {
    var requested=0; private set
    var returned=0; private set
    var canceled=0; private set
    private var inFlight=false
    val delegate = GeckoRuntime.ActivityDelegate { intent ->
        requested++
        val tab=owner.scheduler.selectedId?.let(owner.scheduler::tab)
        val id=tab?.id;val identity=tab?.identity;val epoch=tab?.epoch
        if(inFlight || id==null || identity==null || !TransferPolicy.secureWebAuthnContext(tab.url)) {
            canceled++;GeckoResult.fromException<Intent>(IllegalStateException("No eligible secure-context tab"))
        } else {
            val result=GeckoResult<Intent>()
            inFlight=true
            owner.dispatch(dev.velocity.browser.tabs.Event.RiskChanged(id,identity,Protection.WEBAUTHN,true))
            owner.externalActivities.launch(id,intent) { data ->
                inFlight=false
                if(data!=null && owner.isCurrent(id,identity) && owner.scheduler.tab(id)?.epoch==epoch && owner.scheduler.selectedId==id) { returned++;result.complete(data) }
                else { canceled++;result.completeExceptionally(IllegalStateException("Authentication activity canceled or tab changed")) }
                if(owner.isCurrent(id,identity)) owner.dispatch(dev.velocity.browser.tabs.Event.RiskChanged(id,identity,Protection.WEBAUTHN,false))
            }
            result
        }
    }
}
