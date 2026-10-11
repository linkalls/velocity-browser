'use strict';
let normal = VelocityRules.compile(''), privateRules = normal;
let enabled = false, privateEnabled = false, exceptions = new Set(), privateExceptions = new Set();
let port = null, retries = 0, configured = false, generation = 0, blocked = 0, privateBlocked = 0, statsPending = false;
function reportStats() {
  if (statsPending) return; statsPending = true;
  setTimeout(() => { statsPending = false; if (port && configured) port.postMessage({type:"stats",generation,blocked,privateBlocked}); }, 1000);
}
const topOrigins = new Map();
function validOrigins(list) {
  if (!Array.isArray(list) || list.length > 1000) throw Error('invalid origins');
  return new Set(list.map(s => { const u = new URL(s); if (!['https:', 'http:'].includes(u.protocol) || u.origin !== s) throw Error('invalid origin'); return s; }));
}
function excluded(origin, incognito) { return (incognito ? privateExceptions : exceptions).has(origin); }
function notify() { browser.tabs.query({}).then(tabs => { for (const tab of tabs) browser.tabs.sendMessage(tab.id, {type:'policyUpdated'}).catch(() => {}); }).catch(() => {}); }
function connect() {
  try {
    port = browser.runtime.connectNative('velocity_block_lite');
    port.onMessage.addListener(message => {
      try {
        if (!message || message.type !== 'config' || typeof message.enabled !== 'boolean' || typeof message.privateEnabled !== 'boolean' || !Number.isSafeInteger(message.generation)) return;
        const nextNormal = VelocityRules.compile(message.rules), nextPrivate = message.privateRules === message.rules ? nextNormal : VelocityRules.compile(message.privateRules);
        const nextExceptions = validOrigins(message.exceptions), nextPrivateExceptions = validOrigins(message.privateExceptions);
        normal = nextNormal; privateRules = nextPrivate; exceptions = nextExceptions; privateExceptions = nextPrivateExceptions;
        generation = message.generation; blocked = 0; privateBlocked = 0; enabled = message.enabled; privateEnabled = message.privateEnabled; configured = true; retries = 0;
        port.postMessage({type:'ready', generation:message.generation, accepted:normal.count, skipped:normal.skipped}); notify();
      } catch { port.postMessage({type:'error', code:'CONFIG_INVALID'}); }
    });
    port.onDisconnect.addListener(() => { port = null; configured = false; enabled = false; privateEnabled = false; notify(); if (retries++ < 5) setTimeout(connect, 1000 * retries); });
  } catch { if (retries++ < 5) setTimeout(connect, 1000 * retries); }
}
browser.webRequest.onBeforeRequest.addListener(details => {
  let origin; try { origin = new URL(details.url).origin; } catch { return {}; }
  if (details.type === 'main_frame' && details.tabId >= 0) {
    if (!topOrigins.has(details.tabId) && topOrigins.size >= 8) topOrigins.delete(topOrigins.keys().next().value);
    topOrigins.set(details.tabId, origin);
  }
  const incognito = details.incognito === true;
  if (!configured || !(incognito ? privateEnabled : enabled) || details.tabId < 0 || excluded(topOrigins.get(details.tabId) || origin, incognito)) return {};
  const cancel = VelocityRules.blocked(incognito ? privateRules : normal, details.url, details.type);
  if (cancel) { if (incognito) privateBlocked = Math.min(1000000, privateBlocked + 1); else blocked = Math.min(1000000, blocked + 1); reportStats(); }
  return {cancel};
}, {urls:['http://*/*', 'https://*/*']}, ['blocking']);
browser.tabs.onRemoved.addListener(id => topOrigins.delete(id));
browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || message.type !== 'cosmetic' || !sender.tab || !sender.url) return Promise.resolve({css:''});
  let u; try { u = new URL(sender.url); } catch { return Promise.resolve({css:''}); }
  const incognito = sender.tab.incognito === true;
  return Promise.resolve({css:configured && (incognito ? privateEnabled : enabled) && !excluded(u.origin, incognito) ? VelocityRules.styles(incognito ? privateRules : normal, sender.url) : ''});
});
connect();
