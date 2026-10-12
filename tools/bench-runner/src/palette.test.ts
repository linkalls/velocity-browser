import {expect,test} from 'bun:test';
import {readFileSync} from 'node:fs';
const xml=readFileSync(new URL('../../../app/src/main/res/values/colors.xml',import.meta.url),'utf8');
const colors=new Map([...xml.matchAll(/name="browser_(light|dark)_([a-z_]+)">#([0-9A-Fa-f]{6})/g)].map(m=>[`${m[1]}_${m[2]}`,m[3]!]));
function luminance(hex:string){const rgb=[0,2,4].map(i=>parseInt(hex.slice(i,i+2),16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return rgb[0]!*.2126+rgb[1]!*.7152+rgb[2]!*.0722;}
function ratio(a:string,b:string){const x=luminance(a),y=luminance(b);return(Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
for(const mode of ['light','dark'])test(`${mode} semantic text and controls meet WCAG AA contrast`,()=>{
 const color=(name:string)=>colors.get(`${mode}_${name}`)!;
 for(const fg of ['text','muted','accent','danger','private'])for(const bg of ['background','surface','card','pressed'])expect(ratio(color(fg),color(bg))).toBeGreaterThanOrEqual(4.5);
 expect(ratio(color('on_accent'),color('accent'))).toBeGreaterThanOrEqual(4.5);
});
