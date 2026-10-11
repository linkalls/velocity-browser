'use strict';
// Page-local reversible state. No form reads, screenshots or page text cross messaging.
let sheet=null,generation=0,policy=null,timer=null,observer=null,stopped=false;
const hiddenSet=new Set(),collapsed=new Set(),queue=new Set(),candidates=new Set();
let hiddenRecheck=null,collapsedRecheck=null;
const wrapper='.ad-wrapper,.ad-container,[data-ad-container],[data-ad-wrapper]';
const protectedSelector='html,body,main,article,header,nav,footer,'+VelocityRules.protectedNodes;
const marker='data-velocity-lite-collapsed';
let markerSheet=null,lastScrollAt=-Infinity;
const scrollQuietMs=180;
// Capture nested scrolling too; this passive handler only records time.
window.addEventListener('scroll',()=>{lastScrollAt=performance.now();},{capture:true,passive:true});
function safe(element){return element instanceof Element&&!element.matches(protectedSelector)&&!element.closest('form,[role="dialog"]')&&!element.querySelector(VelocityRules.protectedNodes);}
function stop(){
 if(observer)observer.disconnect();observer=null;
 if(timer!==null)clearTimeout(timer);timer=null;
 queue.clear();candidates.clear();hiddenRecheck=null;collapsedRecheck=null;
}
function restore(){stop();if(sheet)sheet.remove();sheet=null;if(markerSheet)markerSheet.remove();markerSheet=null;for(const e of collapsed)e.removeAttribute(marker);collapsed.clear();hiddenSet.clear();}
function report(){browser.runtime.sendMessage({type:'cosmeticStats',generation:policy?.generation,hidden:hiddenSet.size,collapsed:collapsed.size,stopped}).catch(()=>{});}
function emptyDedicated(e){
 if(!safe(e)||!e.matches(wrapper)||e.contains(document.activeElement))return false;
 // Only emptiness of local text nodes; no aggregate text or input values are read.
 for(const n of e.childNodes){
  if(n.nodeType===Node.TEXT_NODE && /\S/.test(n.nodeValue||''))return false;
  if(n instanceof Element && getComputedStyle(n).display!=='none'){
   if(n.matches('a,iframe,img,video,audio,canvas')||n.querySelector('a,iframe,img,video,audio,canvas,'+VelocityRules.protectedNodes))return false;
   if(n.childNodes.length)return false;
  }
 }
 return true;
}
function enqueue(root){
 if(!(root instanceof Element)||!policy||stopped)return;
 if(queue.size>=100){stopped=true;stop();report();return;}
 queue.add(root);if(!timer)timer=setTimeout(batch,150);
}
function batch(){
 timer=null;if(!policy||document.hidden||stopped)return;
 // Only inspect after scrolling stops. CSS blocking remains immediate and active.
 const quietRemaining=scrollQuietMs-(performance.now()-lastScrollAt);
 if(quietRemaining>0){timer=setTimeout(batch,quietRemaining);return;}
 const started=performance.now();
 // One 32 ms callback can miss four frames on a 120 Hz screen. Instead yield
 // between small slices. A single querySelectorAll call is still not preemptible.
 const budgetMs=4,maxElements=12;
 let examined=0;
 const room=()=>examined<maxElements && performance.now()-started<budgetMs;
 if(hiddenRecheck===null){hiddenRecheck=[...hiddenSet];collapsedRecheck=[...collapsed];}
 while(hiddenRecheck.length && room()){
  const e=hiddenRecheck.pop();
  if(!e.isConnected||getComputedStyle(e).display!=='none')hiddenSet.delete(e);
  examined++;
 }
 while(!hiddenRecheck.length && collapsedRecheck.length && room()){
  const e=collapsedRecheck.pop();
  if(!e.isConnected||!emptyDedicated(e)){e.removeAttribute(marker);collapsed.delete(e);}
  examined++;
 }
 const selectors=policy.selectors||[];
 const matchingSelector=[wrapper,...selectors].join(',');
 // Never lose unfinished roots or candidates when yielding between frames.
 while(!hiddenRecheck.length && !collapsedRecheck.length && queue.size && room() && !stopped){
  const root=queue.values().next().value;queue.delete(root);
  if(!root.isConnected){examined++;continue;}
  if(root.matches(wrapper)||selectors.some(s=>root.matches(s)))candidates.add(root);
  for(const e of root.querySelectorAll(matchingSelector)){
   if(candidates.size>=100){stopped=true;break;}
   candidates.add(e);
  }
  examined++;
 }
 while(!hiddenRecheck.length && !collapsedRecheck.length && !queue.size && candidates.size && room() && !stopped){
  const e=candidates.values().next().value;candidates.delete(e);examined++;
  if(!e.isConnected||!safe(e))continue;
  if(selectors.some(s=>e.matches(s))&&getComputedStyle(e).display==='none'&&hiddenSet.size<100)hiddenSet.add(e);
  if(!policy.collapse)continue;
  let parent=e.matches(wrapper)?e:e.parentElement;
  for(let depth=0;parent&&depth<3;depth++,parent=parent.parentElement){
   if(emptyDedicated(parent)&&collapsed.size<100){parent.setAttribute(marker,'');collapsed.add(parent);}
  }
 }
 if(stopped){stop();report();return;}
 if(hiddenRecheck.length||collapsedRecheck.length||queue.size||candidates.size){
  timer=setTimeout(batch,16); // Yield the main thread before doing more layout work.
 }else{
  hiddenRecheck=null;collapsedRecheck=null;report();
 }
}
function watch(){
 stop();if(!policy||document.hidden||stopped)return;
 observer=new MutationObserver(records=>{
  for(const record of records){
   // Local subtree mutations, never recurring full-document scans.
   if(record.target instanceof Element && record.target.closest(wrapper))enqueue(record.target.closest(wrapper));
   for(const node of record.addedNodes)if(node instanceof Element)enqueue(node);
   if(stopped)break;
  }
 });
 observer.observe(document.documentElement,{childList:true,subtree:true});
 enqueue(document.documentElement);
}
async function refresh(){
 const request=++generation;
 try{
  const result=await browser.runtime.sendMessage({type:'cosmetic'});
  if(request!==generation)return;
  restore();policy=null;stopped=false;
  if(!result||typeof result.css!=='string'||result.css.length>=65536||!result.css){if(typeof pickerExit==='function')pickerExit();return;}
  if(!Array.isArray(result.selectors)||result.selectors.length>128||result.selectors.some(s=>!VelocityRules.selector(s)))return;
  policy=result;sheet=document.createElement('style');sheet.textContent=result.css;(document.head||document.documentElement).appendChild(sheet);
  markerSheet=document.createElement('style');markerSheet.textContent='['+marker+']{display:none!important;margin:0!important;padding:0!important;min-height:0!important;}';(document.head||document.documentElement).appendChild(markerSheet);
  watch();
 }catch{if(request===generation){restore();policy=null;}}
}
browser.runtime.onMessage.addListener(message=>{if(message?.type==='policyUpdated')return refresh();});
document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();else watch();});
window.addEventListener('pagehide',()=>{stop();});window.addEventListener('pageshow',()=>{refresh();});
window.addEventListener('popstate',()=>{refresh();});window.addEventListener('hashchange',()=>{refresh();});
if(document.documentElement)refresh();else document.addEventListener('DOMContentLoaded',refresh,{once:true});
