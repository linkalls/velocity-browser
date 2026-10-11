'use strict';
// Isolated content script. Only canonical origin and bounded selector leave the page.
let pickerExit=null;
browser.runtime.onMessage.addListener(message=>{
 if(message?.type!=='startPicker')return;
 if(pickerExit)pickerExit();
 const requestId=message.requestId,origin=location.origin;
 let selected=null,previousChild=null,preview=false,originalOutline='',originalPriority='';
 const panel=document.createElement('div');panel.setAttribute('data-velocity-picker-panel','');
 panel.style.cssText='position:fixed!important;bottom:12px!important;left:12px!important;right:12px!important;z-index:2147483647!important;padding:12px!important;background:#14232a!important;color:#ffffff!important;border-radius:16px!important;font:16px sans-serif!important;display:flex!important;flex-wrap:wrap!important;gap:8px!important;';
 const status=document.createElement('span');status.textContent='隠す領域をタップ（フォーム・本文は保護）';status.style.width='100%';panel.appendChild(status);
 const previewSheet=document.createElement('style');document.documentElement.appendChild(previewSheet);
 const denied='html,body,main,article,header,nav,footer,'+VelocityRules.protectedNodes;
 function safe(e){if(!(e instanceof Element)||panel.contains(e)||e.matches(denied)||e.closest('form,[role="dialog"]')||e.querySelector(VelocityRules.protectedNodes))return false;const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!(r.width>innerWidth*.8&&r.height>innerHeight*.8);}
 function proposal(e){
  const attempts=[];if(e.id)attempts.push('#'+e.id);for(const c of [...e.classList].slice(0,8))attempts.push('.'+c);
  for(const attribute of e.attributes)if(/^data-ad-[a-z0-9-]{1,40}$/.test(attribute.name))attempts.push('['+attribute.name+']');
  return attempts.find(s=>VelocityRules.selector(s)===s&&document.querySelectorAll(s).length===1)||null;
 }
 function unhighlight(){if(selected){if(originalOutline)selected.style.setProperty('outline',originalOutline,originalPriority);else selected.style.removeProperty('outline');}previewSheet.textContent='';preview=false;}
 function choose(e){if(!safe(e)){status.textContent='この領域は保護されています';return;}unhighlight();selected=e;originalOutline=e.style.getPropertyValue('outline');originalPriority=e.style.getPropertyPriority('outline');e.style.setProperty('outline','3px solid #20c9a8','important');status.textContent=proposal(e)?'選択範囲を確認してください':'一意なID・クラスを持つ小さな領域を選んでください';}
 function finish(){unhighlight();panel.remove();previewSheet.remove();document.removeEventListener('click',click,true);for(const name of ['pointerdown','pointerup','touchstart','touchend'])document.removeEventListener(name,guard,true);window.removeEventListener('pagehide',finish);document.removeEventListener('visibilitychange',hidden);pickerExit=null;}
 function hidden(){if(document.hidden)finish();}
 function guard(event){if(!panel.contains(event.target)){event.stopImmediatePropagation();}}
 function click(event){if(panel.contains(event.target))return;event.preventDefault();event.stopImmediatePropagation();choose(event.target);}
 function button(label,action){const b=document.createElement('button');b.textContent=label;b.style.cssText='min-height:48px!important;min-width:48px!important;font:16px sans-serif!important;border:0!important;border-radius:8px!important;padding:8px!important;background:#fff!important;color:#14232a!important;';b.addEventListener('click',action);panel.appendChild(b);}
 button('範囲を広げる',()=>{if(selected?.parentElement&&safe(selected.parentElement)){previousChild=selected;choose(selected.parentElement);}});
 button('範囲を戻す',()=>{if(previousChild)choose(previousChild);});
 button('プレビュー',()=>{if(!selected)return;const s=proposal(selected);if(!s||!safe(selected))return;preview=!preview;previewSheet.textContent=preview?s+'{display:none!important;}':'';});
 button('適用',async()=>{
  if(!selected)return;previewSheet.textContent='';preview=false;
  const selector=proposal(selected);if(!selector||!safe(selected)){status.textContent='安全な一意の領域を選んでください';return;}
  try{const result=await browser.runtime.sendMessage({type:'pickerSave',requestId,selector});if(result?.success){finish();return;}status.textContent='保存できませんでした。ページやモードの変更を確認してください';}catch{status.textContent='保存できませんでした';}
 });
 button('キャンセル / 元に戻す',finish);
 document.documentElement.appendChild(panel);document.addEventListener('click',click,true);for(const name of ['pointerdown','pointerup','touchstart','touchend'])document.addEventListener(name,guard,{capture:true,passive:false});window.addEventListener('pagehide',finish,{once:true});document.addEventListener('visibilitychange',hidden);pickerExit=finish;
 return Promise.resolve({started:true,origin});
});
