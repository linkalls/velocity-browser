import {expect,test} from 'bun:test';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
const source=(name:string)=>readFileSync(resolve(import.meta.dir,'../../../app/src/main/java/dev/velocity/browser/ui/',name),'utf8');
test('settings updates have no timer polling',()=>{
 const s=source('SettingsView.kt');expect(s).not.toMatch(/postDelayed|setInterval|ticker/);expect(s).toContain('unregisterOnSharedPreferenceChangeListener');
});
test('toolbar never owns, detaches or reloads Gecko sessions and never polls page scripts',()=>{
 const s=source('BrowserChromeView.kt');expect(s).not.toMatch(/GeckoSession\(|releaseSession\(|setSession\(|loadUri\(|\.reload\(|evaluateJavaScript|setInterval|postDelayed/);
});
