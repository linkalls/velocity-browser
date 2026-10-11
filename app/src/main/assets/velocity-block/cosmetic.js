'use strict';
let sheet = null, generation = 0;
async function refresh() {
  const request = ++generation;
  try {
    const result = await browser.runtime.sendMessage({type:'cosmetic'});
    if (request !== generation) return;
    if (sheet) sheet.remove(); sheet = null;
    if (result && typeof result.css === 'string' && result.css.length < 65536 && result.css) {
      sheet = document.createElement('style'); sheet.textContent = result.css;
      (document.head || document.documentElement).appendChild(sheet);
    }
  } catch { if (sheet) sheet.remove(); sheet = null; }
}
browser.runtime.onMessage.addListener(message => { if (message && message.type === 'policyUpdated') refresh(); });
if (document.documentElement) refresh(); else document.addEventListener('DOMContentLoaded', refresh, {once:true});
// No MutationObserver, timer, form reads, persistent storage or outgoing fetch.
