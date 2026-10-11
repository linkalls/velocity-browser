import {expect,test} from 'bun:test';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {runInNewContext} from 'node:vm';
function fixture(){
 let now=0,scans=0,next=1;
 const events:Record<string,()=>void>={},timers=new Map<number,{at:number,run:()=>void}>();
 class Element {
  childNodes:unknown[]=[];isConnected=true;
  matches(){return false;}closest(){return null;}querySelector(){return null;}
  querySelectorAll(){scans++;return [];}appendChild(){}remove(){}setAttribute(){}removeAttribute(){}
 }
 const document={hidden:false,documentElement:new Element(),head:new Element(),activeElement:null,
  createElement:()=>new Element(),addEventListener:(type:string,run:()=>void)=>{events[type]=run;}};
 const context={Element,document,Node:{TEXT_NODE:3},performance:{now:()=>now},
  VelocityRules:{protectedNodes:'form,input',selector:(s:string)=>s},
  window:{addEventListener:(type:string,run:()=>void)=>{events[type]=run;}},
  MutationObserver:class {observe(){}disconnect(){}},
  setTimeout:(run:()=>void,delay:number)=>{const id=next++;timers.set(id,{at:now+delay,run});return id;},
  clearTimeout:(id:number)=>timers.delete(id),
  browser:{runtime:{onMessage:{addListener(){}},sendMessage:(m:{type:string})=>Promise.resolve(m.type==='cosmetic'?{css:'.ad{display:none}',selectors:['.ad'],collapse:true,generation:1}:{})}}
 };
 runInNewContext(readFileSync(resolve(import.meta.dir,'../../../app/src/main/assets/velocity-block/cosmetic.js'),'utf8'),context);
 function advance(to:number){now=to;for(const [id,timer] of [...timers])if(timer.at<=now){timers.delete(id);timer.run();}}
 return {events,document,advance,scans:()=>scans,pending:()=>timers.size,ready:runInNewContext("refresh()",context)};
}
// Tests scheduling of the real script; this does not measure frame rate.
test('cosmetic DOM inspection waits for scrolling to settle, including repeated motion',async()=>{
 const f=fixture();await f.ready;expect(f.pending()).toBe(1);
 f.advance(100);f.events.scroll!();f.advance(150);expect(f.scans()).toBe(0);
 f.advance(250);f.events.scroll!();f.advance(280);expect(f.scans()).toBe(0);
 f.advance(430);expect(f.scans()).toBe(1);expect(f.pending()).toBe(0);
});
test('hidden pages cancel pending cosmetic DOM inspection',async()=>{
 const f=fixture();await f.ready;expect(f.pending()).toBe(1);
 f.advance(100);f.events.scroll!();f.advance(150);
 f.document.hidden=true;f.events.visibilitychange!();f.advance(1000);
 expect(f.scans()).toBe(0);expect(f.pending()).toBe(0);
 f.document.hidden=false;f.events.visibilitychange!();f.advance(1150);expect(f.scans()).toBe(1);
});
