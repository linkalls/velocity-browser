'use strict';
let normal = VelocityRules.compile(''), privateRules = normal;
let enabled = false, privateEnabled = false, exceptions = new Set(), privateExceptions = new Set();
let cosmeticEnabled=true,collapseEnabled=true,collapseExceptions=new Set(),privateCollapseExceptions=new Set();
const cosmeticCounts=new Map();
let port = null, retries = 0, configured = false, generation = 0, blocked = 0, privateBlocked = 0, statsPending = false;
function reportStats() {
  if (statsPending) return; statsPending = true;
  setTimeout(() => { statsPending = false; if (port && configured) port.postMessage({type:"stats",generation,blocked,privateBlocked,
 hidden:[...cosmeticCounts.values()].filter(v=>!v.private).reduce((a,v)=>a+v.hidden,0),collapsed:[...cosmeticCounts.values()].filter(v=>!v.private).reduce((a,v)=>a+v.collapsed,0),
 privateHidden:[...cosmeticCounts.values()].filter(v=>v.private).reduce((a,v)=>a+v.hidden,0),privateCollapsed:[...cosmeticCounts.values()].filter(v=>v.private).reduce((a,v)=>a+v.collapsed,0)}); }, 1000);
}
const topOrigins = new Map();
function validOrigins(list) {
  if (!Array.isArray(list) || list.length > 1000) throw Error('invalid origins');
  return new Set(list.map(s => { const u = new URL(s); if (!['https:', 'http:'].includes(u.protocol) || u.origin !== s) throw Error('invalid origin'); return s; }));
}
function excluded(origin, incognito) { return (incognito ? privateExceptions : exceptions).has(origin); }
async function notify() { const tabs=await browser.tabs.query({});await Promise.all(tabs.map(tab=>browser.tabs.sendMessage(tab.id,{type:'policyUpdated'}).catch(()=>{}))); }
function connect() {
  try {
    port = browser.runtime.connectNative('velocity_block_lite');
    port.onMessage.addListener(async message => {
      try {
        if (!message || message.type !== 'config' || typeof message.enabled !== 'boolean' || typeof message.privateEnabled !== 'boolean' || !Number.isSafeInteger(message.generation)) return;
        const nextNormal = VelocityRules.compile(message.rules), nextPrivate = message.privateRules === message.rules ? nextNormal : VelocityRules.compile(message.privateRules);
        const nextExceptions = validOrigins(message.exceptions), nextPrivateExceptions = validOrigins(message.privateExceptions);
        normal = nextNormal; privateRules = nextPrivate; exceptions = nextExceptions; privateExceptions = nextPrivateExceptions;
        cosmeticEnabled=message.cosmeticEnabled!==false;collapseEnabled=message.collapseEnabled!==false;collapseExceptions=validOrigins(message.collapseExceptions||[]);privateCollapseExceptions=validOrigins(message.privateCollapseExceptions||[]);cosmeticCounts.clear();
        generation = message.generation; blocked = 0; privateBlocked = 0; enabled = message.enabled; privateEnabled = message.privateEnabled; configured = true; retries = 0;
        await notify();if(port)port.postMessage({type:'ready', generation:message.generation, accepted:normal.count, skipped:normal.skipped});
      } catch { port.postMessage({type:'error', code:'CONFIG_INVALID'}); }
    });
    port.onDisconnect.addListener(() => { port = null; configured = false; enabled = false; privateEnabled = false; notify(); if (retries++ < 5) setTimeout(connect, 1000 * retries); });
  } catch { if (retries++ < 5) setTimeout(connect, 1000 * retries); }
}
browser.webRequest.onBeforeRequest.addListener(details => {
  let origin; try { origin = new URL(details.url).origin; } catch { return {}; }
  if (details.type === 'main_frame' && details.tabId >= 0) {
    topOrigins.set(details.tabId, origin);
  }
  const incognito = details.incognito === true;
  if (!configured || !(incognito ? privateEnabled : enabled) || details.tabId < 0 || excluded(topOrigins.get(details.tabId) || origin, incognito)) return {};
  const cancel = VelocityRules.blocked(incognito ? privateRules : normal, details.url, details.type);
  if (cancel) { if (incognito) privateBlocked = Math.min(1000000, privateBlocked + 1); else blocked = Math.min(1000000, blocked + 1); reportStats(); }
  return {cancel};
}, {urls:['http://*/*', 'https://*/*']}, ['blocking']);
browser.tabs.onRemoved.addListener(id => {topOrigins.delete(id);cosmeticCounts.delete(id);});
browser.runtime.onMessage.addListener((message,sender)=>{
 if(!message||!sender.tab||!sender.url)return Promise.resolve({css:''});
 let u;try{u=new URL(sender.url);}catch{return Promise.resolve({css:''});}
 const incognito=sender.tab.incognito===true,allowed=configured&&(incognito?privateEnabled:enabled)&&!excluded(topOrigins.get(sender.tab.id)||u.origin,incognito);
 if(message.type==='cosmeticStats'){
  if(allowed&&message.generation===generation&&Number.isInteger(message.hidden)&&message.hidden>=0&&message.hidden<=100&&Number.isInteger(message.collapsed)&&message.collapsed>=0&&message.collapsed<=100){cosmeticCounts.set(sender.tab.id,{hidden:message.hidden,collapsed:message.collapsed,private:incognito});reportStats();}
  return Promise.resolve({});
 }
 if(message.type!=='cosmetic')return Promise.resolve({css:''});
 const index=incognito?privateRules:normal,selectors=allowed&&cosmeticEnabled?VelocityRules.selections(index,sender.url):[];
 if(selectors.length>128)return Promise.resolve({css:'',limited:true});
 return Promise.resolve({css:selectors.length?VelocityRules.styles(index,sender.url):'',selectors,collapse:collapseEnabled&&!(incognito?privateCollapseExceptions:collapseExceptions).has(u.origin),generation});
});
connect();
