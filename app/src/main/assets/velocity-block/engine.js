/* Velocity original code, MIT. No dynamic code, regex rules, DOM traversal or remote scriptlets. */
(function (root) {
  'use strict';
  const safeSelectors = new Set(['.ad-slot', '.ad-banner', '.advertisement', '.velocity-ad', '[data-velocity-ad]', '[id^="bnc_ad_"]']);
  function domain(s) { return s.length <= 253 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/.test(s); }
  function compile(text) {
    if (typeof text !== 'string' || new TextEncoder().encode(text).length > 2097152 || text.includes('\0')) throw Error('invalid rules');
    const deny = new Set(), allow = new Set(), css = new Map(), seen = new Set(); let skipped = 0, count = 0;
    for (let line of text.split(/\r?\n/)) {
      line = line.trim(); if (!line || line.startsWith('!') || line === '[Adblock Plus 1.1]') continue;
      if (seen.has(line)) continue; seen.add(line);
      const m = /^(@@)?\|\|([^\^]+)\^$/.exec(line), parts = line.split('##');
      if (m && domain(m[2])) (m[1] ? allow : deny).add(m[2]);
      else if (parts.length === 2 && domain(parts[0]) && safeSelectors.has(parts[1])) {
        const list = css.get(parts[0]) || new Set(); list.add(parts[1]); css.set(parts[0], list);
      } else { skipped++; continue; }
      if (++count > 20000) throw Error('too many rules');
    }
    return {deny, allow, css, skipped, count};
  }
  function suffixes(host) {
    const parts = host.toLowerCase().replace(/\.$/, '').split('.'), list = [];
    while (parts.length > 1) { list.push(parts.join('.')); parts.shift(); } return list;
  }
  function blocked(index, url, type) {
    if (type === 'main_frame') return false; // main document / user input never silently blocked.
    let u; try { u = new URL(url); } catch { return false; }
    if (!['https:', 'http:'].includes(u.protocol)) return false;
    const names = suffixes(u.hostname);
    return !names.some(h => index.allow.has(h)) && names.some(h => index.deny.has(h));
  }
  function styles(index, url) {
    let u; try { u = new URL(url); } catch { return ''; }
    const result = new Set(); for (const h of suffixes(u.hostname)) for (const s of index.css.get(h) || []) result.add(s);
    // Never hide form controls or containers containing them. CSS automatically covers later ad elements.
    return [...result].map(s => s + ':not(form):not(input):not(textarea):not(select):not(button):not(:has(form,input,textarea,select,button)){display:none!important;}').join('\n');
  }
  root.VelocityRules = {compile, blocked, styles};
})(globalThis);
