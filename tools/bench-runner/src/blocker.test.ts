import { expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { resolve } from 'node:path';
const assets = resolve(import.meta.dir, '../../../app/src/main/assets/velocity-block');
const source = readFileSync(resolve(assets, 'engine.js'), 'utf8');
const context: any = {URL, TextEncoder}; runInNewContext(source, context);
const engine = context.VelocityRules;
test('domain boundaries, exceptions and main document safety', () => {
  const r = engine.compile('||ads.example^\n@@||safe.ads.example^');
  expect(engine.blocked(r, 'https://cdn.ads.example/a', 'script')).toBe(true);
  expect(engine.blocked(r, 'https://notads.example/a', 'script')).toBe(false);
  expect(engine.blocked(r, 'https://safe.ads.example/a', 'image')).toBe(false);
  expect(engine.blocked(r, 'https://ads.example/a', 'main_frame')).toBe(false);
  expect(engine.blocked(r, 'file:///ads.example', 'script')).toBe(false);
});
test('cosmetics scoped and form-safe, unsupported syntax skipped', () => {
  const r = engine.compile('site.example##.ad-slot\nsite.example##body\nsite.example#%#alert(1)\n||ads.example^$script');
  expect(r.skipped).toBe(3);
  expect(engine.styles(r, 'https://site.example/page')).toContain(':not(:has(form,input,textarea,select,button))');
  expect(engine.styles(r, 'https://unrelated.example/page')).toBe('');
});
test('bounded rules cannot inject CSS or execute code', () => {
  expect(() => engine.compile('x'.repeat(2097153))).toThrow();
  const r = engine.compile('site.example##.ad-slot{background:url(https://evil.example)}\nsite.example##script\n||ads.example^$redirect=evil');
  expect(r.count).toBe(0); expect(r.skipped).toBe(3);
});
function background() {
  const handlers: any = {}; const posted: any[] = [];
  const port = {onMessage:{addListener:(f:any)=>handlers.config=f},onDisconnect:{addListener:(f:any)=>handlers.disconnect=f},postMessage:(m:any)=>posted.push(m)};
  const c: any = {URL, TextEncoder, setTimeout:()=>{}, browser:{
    runtime:{connectNative:()=>port,onMessage:{addListener:(f:any)=>handlers.cosmetic=f}},
    webRequest:{onBeforeRequest:{addListener:(f:any)=>handlers.request=f}},
    tabs:{query:()=>Promise.resolve([]),sendMessage:()=>Promise.resolve(),onRemoved:{addListener:(f:any)=>handlers.remove=f}}
  }};
  runInNewContext(source,c); runInNewContext(readFileSync(resolve(assets,'background.js'),'utf8'),c);
  return {handlers,posted};
}
const config = {type:'config',generation:1,enabled:true,privateEnabled:true,rules:'||ads.example^\nsite.example##.ad-slot',privateRules:'||ads.example^',exceptions:['https://allowed.example'],privateExceptions:[]};
test('site exceptions, private separation, atomic failure and disconnect', async () => {
  const {handlers:h,posted} = background();
  const ad = {tabId:1,type:'script',url:'https://ads.example/a',incognito:false};
  expect(h.request(ad)).toEqual({});
  h.config(config); expect(posted[0].type).toBe('ready');
  expect(h.request(ad).cancel).toBe(true);
  h.request({tabId:1,type:'main_frame',url:'https://allowed.example/',incognito:false});
  expect(h.request(ad)).toEqual({});
  expect(h.request({...ad,incognito:true}).cancel).toBe(true);
  h.config({...config,rules:'x'.repeat(2097153)});
  expect(posted.at(-1).code).toBe('CONFIG_INVALID');
  expect(h.request({...ad,incognito:true}).cancel).toBe(true);
  const css = await h.cosmetic({type:'cosmetic'},{tab:{id:2,incognito:false},url:'https://site.example/'});
  expect(css.css).toContain('.ad-slot');
  h.disconnect(); expect(h.request(ad)).toEqual({});
});
