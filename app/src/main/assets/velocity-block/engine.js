/* Velocity original bounded rule subset, MIT. No remote code or scriptlets. */
(function(root){
'use strict';
const builtins=['.ad-slot','.ad-banner','.advertisement','.velocity-ad','[data-velocity-ad]','[id^="bnc_ad_"]','.adsbygoogle','[data-ad-slot]','[data-ad-unit]'];
const protectedNodes='form,input,textarea,select,button,video,audio,canvas,[contenteditable],[role="dialog"],[aria-live]';
const ident='[A-Za-z_][A-Za-z0-9_-]{0,63}';
const token=new RegExp('(?:[#.]'+ident+'|\\[data-ad-[a-z0-9-]{1,40}(?:="[A-Za-z0-9_-]{1,64}")?\\]|\\[(?:id|class)="'+ident+'"\\])','y');
function selector(raw){
 if(typeof raw!=='string'||raw.length>256)return null;
 if(raw==='[id^="bnc_ad_"]'||raw==='[data-velocity-ad]')return raw;
 const s=raw.trim().replace(/\s*>\s*/g,' > ').replace(/\s+/g,' '),parts=s.split(' ');
 if(parts.length>7||parts.length===0)return null;
 let terms=0,previous=false;
 for(const part of parts){
  if(part==='>'){if(!previous)return null;previous=false;continue;}
  if(++terms>4||!part)return null;
  let offset=0,count=0;
  while(offset<part.length){token.lastIndex=offset;const m=token.exec(part);if(!m||++count>3)return null;offset=token.lastIndex;}
  previous=true;
 }
 return previous?s:null;
}
function domain(s){return s.length<=253&&/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/.test(s);}
function compile(text){
 if(typeof text!=='string'||new TextEncoder().encode(text).length>2097152||text.includes('\0'))throw Error('invalid rules');
 const deny=new Set(),allow=new Set(),css=new Map(),exceptCss=new Map(),seen=new Set();let skipped=0,count=0;
 for(let line of text.split(/\r?\n/)){
  line=line.trim();if(!line||line.startsWith('!')||line==='[Adblock Plus 1.1]')continue;
  const n=/^(@@)?\|\|([^\^]+)\^$/.exec(line), c=/^([^#]+)(#@#|##)(.+)$/.exec(line);
  let normalized=line,selection;
  if(n&&domain(n[2])){if(seen.has(line))continue;(n[1]?allow:deny).add(n[2]);}
  else if(c&&domain(c[1])&&(selection=selector(c[3]))){
   normalized=c[1]+c[2]+selection;if(seen.has(normalized))continue;
   const map=c[2]==='#@#'?exceptCss:css,list=map.get(c[1])||new Set();list.add(selection);map.set(c[1],list);
  }else{skipped++;continue;}
  seen.add(normalized);if(++count>20000)throw Error('too many rules');
 }
 return {deny,allow,css,exceptCss,skipped,count};
}
function suffixes(host){const parts=host.toLowerCase().replace(/\.$/,'').split('.'),list=[];while(parts.length>1){list.push(parts.join('.'));parts.shift();}return list;}
function blocked(index,url,type){if(type==='main_frame')return false;let u;try{u=new URL(url);}catch{return false;}if(!['https:','http:'].includes(u.protocol))return false;const names=suffixes(u.hostname);return !names.some(h=>index.allow.has(h))&&names.some(h=>index.deny.has(h));}
function selections(index,url){let u;try{u=new URL(url);}catch{return [];}if(!['http:','https:'].includes(u.protocol))return [];
 const result=new Set(builtins),except=new Set();for(const h of suffixes(u.hostname)){for(const s of index.css.get(h)||[])result.add(s);for(const s of index.exceptCss.get(h)||[])except.add(s);}
 return [...result].filter(s=>!except.has(s));
}
function styles(index,url){return selections(index,url).map(s=>s+':not(:is(html,body,main,article,header,nav,footer,'+protectedNodes+')):not(:has('+protectedNodes+')):not(:is(form *,[role="dialog"] *)){display:none!important;}').join('\n');}
root.VelocityRules={compile,blocked,styles,selections,selector,builtins,protectedNodes};
})(globalThis);
